use anyhow::{Context, Result};
use clap::Parser;
use gst::prelude::*;
use gstreamer as gst;
use gstreamer_app as gst_app;
use log::{error, info, warn};

use std::io::Write;
use std::path::Path;
use std::process::Command;
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{Duration, Instant};

#[derive(Parser, Debug)]
#[command(author, version, about, long_about = None)]
struct Args {
    #[arg(short = '4', long, default_value_t = 5000)]
    port_h264: u16,
    #[arg(short = '5', long, default_value_t = 5001)]
    port_h265: u16,
    #[arg(short, long, default_value = "/dev/video10")]
    device: String,

    /// Optional MJPEG Stream URL (e.g., http://192.168.1.5:8080/)
    #[arg(long)]
    mjpeg: Option<String>,
}

struct BridgeState {
    last_sample: Option<gst::Sample>,
    last_update: Instant,
    appsrc: gst_app::AppSrc,
    timestamp_ns: u64,
    active_codec: String,
}

fn main() -> Result<()> {
    // 0. Parse Arguments FIRST
    let args = Args::parse();

    // 1. Auto-Modprobe (Phase 4)
    check_and_load_v4l2loopback(&args.device);

    // 2. Initialize Logging
    if std::env::var("RUST_LOG").is_err() {
        std::env::set_var("RUST_LOG", "info");
    }
    env_logger::init();

    // 3. Start mDNS Service (via avahi-publish)
    std::thread::spawn(move || {
        info!("[mDNS] Attempting to register service using avahi-publish...");

        let output = Command::new("avahi-publish")
            .arg("-s")
            .arg("DroidV4L2 Bridge")
            .arg("_droidv4l2._tcp")
            .arg("5000")
            .spawn();

        match output {
            Ok(mut child) => {
                info!(
                    "[mDNS] avahi-publish started successfully (PID: {})",
                    child.id()
                );
                // Wait for it to exit (it shouldn't, unless error)
                let _ = child.wait();
                warn!("[mDNS] avahi-publish exited unexpectedly!");
            }
            Err(e) => {
                error!("[mDNS] Failed to execute avahi-publish: {}", e);
                info!("[mDNS] Is 'avahi-utils' installed? 'sudo apt install avahi-utils'");
            }
        }
    });

    gst::init().context("GStreamer init failed")?;

    info!("[SYSTEM] GStreamer initialized.");
    check_plugin("h264parse");
    check_plugin("avdec_h264");
    check_plugin("h265parse");
    check_plugin("avdec_h265");
    check_plugin("videotestsrc"); // Check screensaver plugin

    // Check MJPEG plugins if URL provided
    if args.mjpeg.is_some() {
        check_plugin("souphttpsrc");
        check_plugin("multipartdemux");
        check_plugin("jpegdec");
    }

    std::io::stdout().flush().unwrap();

    // --- 1. Persistent Sink Pipeline (Caps Lockdown) ---
    // Caps Lockdown: Force I420 1080p output to V4L2
    let sink_pipeline_str = format!(
        "appsrc name=mysrc is-live=true format=time min-latency=0 caps=\"video/x-raw,format=I420,width=1920,height=1080,framerate=30/1,pixel-aspect-ratio=1/1\" ! videoconvert ! videoscale ! video/x-raw,format=YUY2,width=1920,height=1080 ! v4l2sink device={} sync=false",
        args.device
    );
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

    // --- 2. Screensaver Generator ---
    // Standard SMPTE bars
    let saver_pipeline_str = "videotestsrc pattern=smpte ! videoscale ! video/x-raw,format=I420,width=1920,height=1080 ! appsink name=saver sync=false drop=true max-buffers=1";
    let saver_pipeline = gst::parse_launch(saver_pipeline_str)?;
    let saver_appsink = saver_pipeline
        .downcast_ref::<gst::Bin>()
        .unwrap()
        .by_name("saver")
        .unwrap()
        .downcast::<gst_app::AppSink>()
        .unwrap();

    saver_pipeline.set_state(gst::State::Playing)?;
    println!("[SYSTEM] Screensaver Generator Active (SMPTE Bars)");

    let state = Arc::new(Mutex::new(BridgeState {
        last_sample: None,
        last_update: Instant::now(),
        appsrc: appsrc.clone(),
        timestamp_ns: 0,
        active_codec: "none".to_string(),
    }));

    // --- 3. Watchdog Thread (Keep-alive, Cleanup, Screensaver) ---
    let state_watchdog = Arc::clone(&state);
    thread::spawn(move || {
        loop {
            // 30fps = 33ms
            thread::sleep(Duration::from_millis(33));
            let mut s = state_watchdog.lock().unwrap();
            let elapsed = s.last_update.elapsed();

            if elapsed > Duration::from_millis(500) {
                // State: IDLE (No connection)
                if s.last_sample.is_some() {
                    // Start screensaver logic
                    s.last_sample = None;
                    s.active_codec = "none".to_string();
                }

                // Pull from screensaver pipeline
                match saver_appsink.pull_sample() {
                    Ok(sample) => {
                        // Push screensaver frame to main pipeline
                        push_sample_to_appsrc(&mut s, sample, true);
                    }
                    Err(_) => {
                        // Should not happen
                    }
                }
            } else if elapsed > Duration::from_millis(200) {
                // State: JITTER (Network Jitter)
                // Action: PLC (Packet Loss Concealment - Repeat last frame)
                if let Some(sample) = s.last_sample.clone() {
                    if s.active_codec != "none" {
                        push_sample_to_appsrc(&mut s, sample, true);
                    }
                }
            }
        }
    });

    // --- 4. Start Listeners ---

    // MJPEG Thread (Optional)
    let mjpeg_url = args.mjpeg.clone();
    let state_mjpeg = Arc::clone(&state);
    let mjpeg_thread = if let Some(url) = mjpeg_url {
        Some(thread::spawn(move || {
            run_mjpeg_loop(&url, state_mjpeg);
        }))
    } else {
        None
    };

    // H.264 Listener
    let state_h264 = Arc::clone(&state);
    let port_h264 = args.port_h264;
    let h264_thread = thread::spawn(move || {
        run_source_loop(port_h264, "h264", state_h264);
    });

    // H.265 Listener
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

fn check_plugin(name: &str) {
    let registry = gst::Registry::get();
    match registry.find_feature(name, gst::ElementFactory::static_type()) {
        Some(_) => println!("[CHECK] Plugin '{}' FOUND.", name),
        None => println!(
            "[CHECK] Plugin '{}' NOT FOUND! (Required for feature)",
            name
        ),
    }
}

fn run_mjpeg_loop(url: &str, state: Arc<Mutex<BridgeState>>) {
    println!("[THREAD] Starting MJPEG loop on URL: {}", url);

    loop {
        // Pipeline: curl/souphttpsrc -> multipartdemux -> jpegdec -> scale/convert -> appsink
        // Using souphttpsrc for better HTTP support
        // use add-borders=true to fix "Wide Putin"; enforcing input PAR is critical
        let pipeline_str = format!(
            "souphttpsrc location={} is-live=true do-timestamp=true keep-alive=true ! multipartdemux ! jpegdec ! videoconvert ! video/x-raw,pixel-aspect-ratio=1/1 ! videoscale add-borders=true ! video/x-raw,format=I420,width=1920,height=1080,pixel-aspect-ratio=1/1 ! appsink name=mysink sync=false drop=true max-buffers=1",
            url
        );

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
                            push_sample_to_appsrc(&mut s, sample, false);
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
            Err(e) => {
                println!("[GST-ERR] Failed to launch MJPEG pipeline: {}", e);
            }
        }

        // Wait before reconnecting to avoid spamming
        thread::sleep(Duration::from_secs(2));
    }
}

fn run_source_loop(port: u16, codec: &str, state: Arc<Mutex<BridgeState>>) {
    println!("[THREAD] Starting {} loop on port {}", codec, port);
    loop {
        let parser_decoder = if codec == "h264" {
            "h264parse ! avdec_h264"
        } else {
            "h265parse ! avdec_h265"
        };

        // Use srtserversrc for listening mode? No, previous implementation used srtsrc with mode=listener.
        // Wait, the previous implementation used srtsrc uri=srt://:port?mode=listener

        // Force Scale + Convert to I420 1080p with correct aspect ratio
        // Removed streamid=live constraint to fix handshake failure
        let src_pipeline_str = format!(
            "srtsrc uri=srt://:{}?mode=listener&latency=20&poll-timeout=100 ! tsdemux ! {} max-threads=4 ! videoconvert ! video/x-raw,pixel-aspect-ratio=1/1 ! videoscale add-borders=true ! video/x-raw,format=I420,width=1920,height=1080,pixel-aspect-ratio=1/1 ! appsink name=mysink sync=false drop=true max-buffers=1",
            port, parser_decoder
        );

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
                            push_sample_to_appsrc(&mut s, sample, false);
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

fn push_sample_to_appsrc(s: &mut BridgeState, sample: gst::Sample, _is_keepalive: bool) {
    // Caps Lockdown: 绝不调用 set_caps
    if let Some(buffer) = sample.buffer() {
        let mut new_buffer = gst::Buffer::with_size(buffer.size()).unwrap();
        {
            let b = new_buffer.get_mut().unwrap();
            b.set_pts(gst::ClockTime::from_nseconds(s.timestamp_ns));
            b.set_duration(gst::ClockTime::from_nseconds(33_333_333));
            let map = buffer.map_readable().unwrap();
            let mut new_map = b.map_writable().unwrap();
            new_map.copy_from_slice(&map);
        }
        let _ = s.appsrc.push_buffer(new_buffer);
        s.timestamp_ns += 33_333_333;
    }
}

fn check_and_load_v4l2loopback(device_path: &str) {
    if Path::new(device_path).exists() {
        println!("[Check] Device {} exists.", device_path);
        return;
    }

    println!(
        "[Warning] Device {} NOT found. Attempting to load v4l2loopback...",
        device_path
    );
    println!("[System] Requesting sudo privileges via pkexec...");

    // Extract video_nr from /dev/video10 -> 10
    let video_nr = device_path.replace("/dev/video", "");

    // Command: pkexec modprobe v4l2loopback video_nr=10 card_label="DroidV4L2" exclusive_caps=1
    let status = Command::new("pkexec")
        .arg("modprobe")
        .arg("v4l2loopback")
        .arg(format!("video_nr={}", video_nr))
        .arg("card_label=DroidV4L2")
        .arg("exclusive_caps=1")
        .status();

    match status {
        Ok(exit_status) => {
            if exit_status.success() {
                println!("[System] v4l2loopback loaded successfully.");
                // Give udev a moment to create the device node
                thread::sleep(Duration::from_millis(500));
            } else {
                eprintln!(
                    "[Error] Failed to load v4l2loopback (exit code: {:?})",
                    exit_status.code()
                );
                // Don't panic here, let GStreamer fail typically if device is still missing
            }
        }
        Err(e) => {
            eprintln!("[Error] Failed to execute pkexec: {}", e);
        }
    }
}
