mod config;
mod pipeline;
mod state;
mod utils;

use anyhow::{Context, Result};
use clap::Parser;
use gst::prelude::*;
use gstreamer as gst;
use gstreamer_app as gst_app;
use log::info;
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{Duration, Instant};

use crate::config::Args;
use crate::state::BridgeState;

fn main() -> Result<()> {
    let args = Args::parse();

    utils::check_and_load_v4l2loopback(&args.device);

    if std::env::var("RUST_LOG").is_err() {
        std::env::set_var("RUST_LOG", "info");
    }
    env_logger::init();

    utils::start_mdns_broadcast();

    gst::init().context("GStreamer init failed")?;
    info!("[SYSTEM] GStreamer initialized.");

    utils::check_plugin("h264parse");
    utils::check_plugin("avdec_h264");
    utils::check_plugin("h265parse");
    utils::check_plugin("avdec_h265");
    utils::check_plugin("videotestsrc");

    if args.mjpeg.is_some() {
        utils::check_plugin("souphttpsrc");
        utils::check_plugin("multipartdemux");
        utils::check_plugin("jpegdec");
    }

    let sink_pipeline_str = pipeline::make_sink_pipeline_str(&args.device);
    let sink_pipeline = gst::parse_launch(&sink_pipeline_str)?;
    let appsrc = sink_pipeline
        .downcast_ref::<gst::Bin>()
        .unwrap()
        .by_name("mysrc")
        .unwrap()
        .downcast::<gst_app::AppSrc>()
        .unwrap();

    sink_pipeline.set_state(gst::State::Playing)?;
    println!(
        "[SYSTEM] Permanent Sink Active on {} (Caps Locked)",
        args.device
    );

    let saver_pipeline_str = pipeline::make_saver_pipeline_str();
    let saver_pipeline = gst::parse_launch(&saver_pipeline_str)?;
    let saver_appsink = saver_pipeline
        .downcast_ref::<gst::Bin>()
        .unwrap()
        .by_name("saver")
        .unwrap()
        .downcast::<gst_app::AppSink>()
        .unwrap();

    saver_pipeline.set_state(gst::State::Playing)?;
    println!("[SYSTEM] Screensaver Generator Active (SMPTE Bars)");

    let state = Arc::new(Mutex::new(BridgeState::new(appsrc)));

    let state_watchdog = Arc::clone(&state);
    thread::spawn(move || loop {
        thread::sleep(Duration::from_millis(33));
        let mut s = state_watchdog.lock().unwrap();
        let elapsed = s.last_update.elapsed();

        if elapsed > Duration::from_millis(500) {
            if s.last_sample.is_some() {
                s.last_sample = None;
                s.active_codec = "none".to_string();
            }

            if let Ok(sample) = saver_appsink.pull_sample() {
                s.push_sample_to_appsrc(sample);
            }
        } else if elapsed > Duration::from_millis(200) {
            if let Some(sample) = s.last_sample.clone() {
                if s.active_codec != "none" {
                    s.push_sample_to_appsrc(sample);
                }
            }
        }
    });

    let mjpeg_url = args.mjpeg.clone();
    let state_mjpeg = Arc::clone(&state);
    let mjpeg_thread = if let Some(url) = mjpeg_url {
        Some(thread::spawn(move || {
            run_mjpeg_loop(&url, state_mjpeg);
        }))
    } else {
        None
    };

    let state_h264 = Arc::clone(&state);
    let port_h264 = args.port_h264;
    let h264_thread = thread::spawn(move || {
        run_source_loop(port_h264, "h264", state_h264);
    });

    let state_h265 = Arc::clone(&state);
    let port_h265 = args.port_h265;
    let h265_thread = thread::spawn(move || {
        run_source_loop(port_h265, "h265", state_h265);
    });

    h264_thread.join().unwrap();
    h265_thread.join().unwrap();
    if let Some(t) = mjpeg_thread {
        t.join().unwrap();
    }

    Ok(())
}

fn run_mjpeg_loop(url: &str, state: Arc<Mutex<BridgeState>>) {
    println!("[THREAD] Starting MJPEG loop on URL: {}", url);
    loop {
        let pipeline_str = pipeline::make_mjpeg_pipeline_str(url);
        match gst::parse_launch(&pipeline_str) {
            Ok(pipe) => {
                println!("[NETWORK] MJPEG Listener CONNECTED to {}", url);
                let appsink = pipe
                    .downcast_ref::<gst::Bin>()
                    .unwrap()
                    .by_name("mysink")
                    .unwrap()
                    .downcast::<gst_app::AppSink>()
                    .unwrap();

                let state_input = Arc::clone(&state);
                appsink.set_callbacks(
                    gst_app::AppSinkCallbacks::builder()
                        .new_sample(move |sink| {
                            let sample = sink.pull_sample().map_err(|_| gst::FlowError::Error)?;
                            let mut s = state_input.lock().unwrap();

                            if s.active_codec != "mjpeg" {
                                println!("[SWITCH] Codec changed: {} -> mjpeg", s.active_codec);
                                s.active_codec = "mjpeg".to_string();
                            }

                            s.last_sample = Some(sample.clone());
                            s.last_update = Instant::now();
                            s.push_sample_to_appsrc(sample);
                            Ok(gst::FlowSuccess::Ok)
                        })
                        .build(),
                );

                if let Err(e) = pipe.set_state(gst::State::Playing) {
                    println!("[GST-ERR] MJPEG set_state(Playing) failed: {}", e);
                } else {
                    let bus = pipe.bus().unwrap();
                    for msg in bus.iter_timed(gst::ClockTime::NONE) {
                        use gst::MessageView;
                        match msg.view() {
                            MessageView::Error(err) => {
                                println!("[GST-ERR] MJPEG Pipeline Error: {}", err.error());
                                break;
                            }
                            MessageView::Eos(..) => {
                                println!("[GST-EOS] MJPEG EOS");
                                break;
                            }
                            _ => (),
                        }
                    }
                }
                let _ = pipe.set_state(gst::State::Null);
                println!("[NETWORK] MJPEG Listener DISCONNECTED (restarting...)");
            }
            Err(e) => println!("[GST-ERR] Failed to launch MJPEG pipeline: {}", e),
        }
        thread::sleep(Duration::from_secs(2));
    }
}

fn run_source_loop(port: u16, codec: &str, state: Arc<Mutex<BridgeState>>) {
    println!("[THREAD] Starting {} loop on port {}", codec, port);
    loop {
        let src_pipeline_str = pipeline::make_source_pipeline_str(port, codec);
        match gst::parse_launch(&src_pipeline_str) {
            Ok(src_pipe) => {
                println!("[NETWORK] {} Listener OPEN on port {}", codec, port);
                let appsink = src_pipe
                    .downcast_ref::<gst::Bin>()
                    .unwrap()
                    .by_name("mysink")
                    .unwrap()
                    .downcast::<gst_app::AppSink>()
                    .unwrap();

                let state_input = Arc::clone(&state);
                let codec_name = codec.to_string();

                appsink.set_callbacks(
                    gst_app::AppSinkCallbacks::builder()
                        .new_sample(move |sink| {
                            let sample = sink.pull_sample().map_err(|_| gst::FlowError::Error)?;
                            let mut s = state_input.lock().unwrap();

                            if s.active_codec != codec_name {
                                println!(
                                    "[SWITCH] Codec changed: {} -> {}",
                                    s.active_codec, codec_name
                                );
                                s.active_codec = codec_name.clone();
                            }

                            s.last_sample = Some(sample.clone());
                            s.last_update = Instant::now();
                            s.push_sample_to_appsrc(sample);
                            Ok(gst::FlowSuccess::Ok)
                        })
                        .build(),
                );

                if let Err(e) = src_pipe.set_state(gst::State::Playing) {
                    println!("[GST-ERR] {} set_state(Playing) failed: {}", codec, e);
                }

                let bus = src_pipe.bus().unwrap();
                for msg in bus.iter_timed(gst::ClockTime::NONE) {
                    use gst::MessageView;
                    match msg.view() {
                        MessageView::Error(_) | MessageView::Eos(..) => break,
                        _ => (),
                    }
                }
                src_pipe.set_state(gst::State::Null).unwrap();
                println!("[NETWORK] {} Listener CLOSED (restarting...)", codec);
            }
            Err(e) => {
                println!("[GST-ERR] Failed to launch {} pipeline: {}", codec, e);
                thread::sleep(Duration::from_secs(1));
            }
        }
        thread::sleep(Duration::from_millis(10));
    }
}
