use anyhow::{Result, Context};
use clap::Parser;
use gstreamer as gst;
use gstreamer_app as gst_app;
use gst::prelude::*;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};
use std::thread;

#[derive(Parser, Debug)]
#[command(author, version, about, long_about = None)]
struct Args {
    #[arg(short, long, default_value_t = 5000)]
    port: u16,
    #[arg(short, long, default_value = "/dev/video10")]
    device: String,
}

struct BridgeState {
    last_sample: Option<gst::Sample>,
    last_update: Instant,
    appsrc: gst_app::AppSrc,
    timestamp_ns: u64,
}

fn main() -> Result<()> {
    let args = Args::parse();
    gst::init().context("GStreamer init failed")?;

    let sink_pipeline_str = format!(
        "appsrc name=mysrc is-live=true format=time min-latency=0 ! videoconvert ! video/x-raw,format=YUY2 ! v4l2sink device={} sync=false",
        args.device
    );
    let sink_pipeline = gst::parse_launch(&sink_pipeline_str)?;
    let appsrc = sink_pipeline
        .downcast_ref::<gst::Bin>().unwrap()
        .by_name("mysrc").unwrap()
        .downcast::<gst_app::AppSrc>().unwrap();

    sink_pipeline.set_state(gst::State::Playing)?;
    println!("[SYSTEM] Permanent Sink Active on {}", args.device);

    let state = Arc::new(Mutex::new(BridgeState {
        last_sample: None,
        last_update: Instant::now(),
        appsrc: appsrc.clone(),
        timestamp_ns: 0,
    }));

    let state_watchdog = Arc::clone(&state);
    thread::spawn(move || {
        loop {
            thread::sleep(Duration::from_millis(33));
            let mut s = state_watchdog.lock().unwrap();
            if s.last_update.elapsed() > Duration::from_millis(200) {
                if let Some(sample) = s.last_sample.clone() {
                    push_sample_to_appsrc(&mut s, sample);
                }
            }
        }
    });

    loop {
        println!("[NETWORK] Waiting for Android stream on port {}...", args.port);
        // 移除 poll-timeout，让它永远监听，防止端口反复开关
        let src_pipeline_str = format!(
            "srtsrc uri=srt://:{}?mode=listener&latency=20&streamid=live ! tsdemux ! h264parse ! avdec_h264 max-threads=4 ! videoconvert ! video/x-raw,format=I420 ! appsink name=mysink sync=false drop=true max-buffers=1",
            args.port
        );

        if let Ok(src_pipe) = gst::parse_launch(&src_pipeline_str) {
            let appsink = src_pipe.downcast_ref::<gst::Bin>().unwrap()
                .by_name("mysink").unwrap()
                .downcast::<gst_app::AppSink>().unwrap();

            let state_input = Arc::clone(&state);
            appsink.set_callbacks(
                gst_app::AppSinkCallbacks::builder()
                    .new_sample(move |sink| {
                        let sample = sink.pull_sample().map_err(|_| gst::FlowError::Error)?;
                        let mut s = state_input.lock().unwrap();
                        s.last_sample = Some(sample.clone());
                        s.last_update = Instant::now();
                        push_sample_to_appsrc(&mut s, sample);
                        Ok(gst::FlowSuccess::Ok)
                    })
                    .build()
            );

            src_pipe.set_state(gst::State::Playing).unwrap();
            let bus = src_pipe.bus().unwrap();
            for msg in bus.iter_timed(gst::ClockTime::NONE) {
                use gst::MessageView;
                match msg.view() {
                    MessageView::Error(_) | MessageView::Eos(..) => break,
                    _ => (),
                }
            }
            src_pipe.set_state(gst::State::Null).unwrap();
        }
        thread::sleep(Duration::from_millis(500));
    }
}

fn push_sample_to_appsrc(s: &mut BridgeState, sample: gst::Sample) {
    if let Some(caps) = sample.caps() {
        s.appsrc.set_caps(Some(&caps.to_owned()));
    }
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