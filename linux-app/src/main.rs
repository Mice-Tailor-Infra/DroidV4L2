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

struct SharedState {
    last_sample: Option<gst::Sample>,
    fps_counter: u32,
}

fn main() -> Result<()> {
    let args = Args::parse();
    gst::init().context("GStreamer init failed")?;

    let shared_state = Arc::new(Mutex::new(SharedState { 
        last_sample: None,
        fps_counter: 0,
    }));

    // --- 1. 持久化输出端 (v4l2sink) ---
    let sink_pipeline_str = format!(
        "appsrc name=mysrc is-live=true format=time ! videoconvert ! v4l2sink device={} sync=false",
        args.device
    );
    let sink_pipeline = gst::parse_launch(&sink_pipeline_str)?;
    let appsrc = sink_pipeline
        .downcast_ref::<gst::Bin>().unwrap()
        .by_name("mysrc").unwrap()
        .downcast::<gst_app::AppSrc>().unwrap();

    sink_pipeline.set_state(gst::State::Playing)?;
    println!("[SYSTEM] Permanent V4L2 Sink Active on {}", args.device);

    // --- 2. 帧注入与保活线程 ---
    let state_clone = Arc::clone(&shared_state);
    let appsrc_clone = appsrc.clone();
    thread::spawn(move || {
        let mut timestamp = 0;
        let mut last_log = Instant::now();
        loop {
            let sample = {
                let s = state_clone.lock().unwrap();
                s.last_sample.clone()
            };

            if let Some(sample) = sample {
                // 动态同步分辨率 (Caps)
                if let Some(caps) = sample.caps() {
                    appsrc_clone.set_caps(Some(&caps.to_owned()));
                }

                let buffer = sample.buffer().unwrap();
                let mut new_buffer = gst::Buffer::with_size(buffer.size()).unwrap();
                {
                    let b = new_buffer.get_mut().unwrap();
                    b.set_pts(gst::ClockTime::from_nseconds(timestamp));
                    b.set_duration(gst::ClockTime::from_nseconds(33_333_333));
                    let map = buffer.map_readable().unwrap();
                    let mut new_map = b.map_writable().unwrap();
                    new_map.copy_from_slice(&map);
                }
                let _ = appsrc_clone.push_buffer(new_buffer);
                timestamp += 33_333_333;
            }

            if last_log.elapsed() >= Duration::from_secs(2) {
                let mut s = state_clone.lock().unwrap();
                if s.fps_counter > 0 {
                    println!("[VIDEO] Status: Streaming Active ({} fps)", s.fps_counter / 2);
                    s.fps_counter = 0;
                }
                last_log = Instant::now();
            }
            thread::sleep(Duration::from_millis(32));
        }
    });

    // --- 3. 拉流循环 ---
    loop {
        println!("[NETWORK] Waiting for Android SRT on port {}...", args.port);
        let src_pipeline_str = format!(
            "srtsrc uri=srt://:{}?mode=listener&latency=20&streamid=live&poll-timeout=1000 ! tsdemux ! h264parse ! avdec_h264 ! videoconvert ! video/x-raw,format=I420 ! appsink name=mysink sync=false",
            args.port
        );

        if let Ok(src_pipe) = gst::parse_launch(&src_pipeline_str) {
            let appsink = src_pipe.downcast_ref::<gst::Bin>().unwrap()
                .by_name("mysink").unwrap()
                .downcast::<gst_app::AppSink>().unwrap();

            let state_input = Arc::clone(&shared_state);
            appsink.set_callbacks(
                gst_app::AppSinkCallbacks::builder()
                    .new_sample(move |sink| {
                        let sample = sink.pull_sample().map_err(|_| gst::FlowError::Error)?;
                        let mut s = state_input.lock().unwrap();
                        s.last_sample = Some(sample);
                        s.fps_counter += 1;
                        Ok(gst::FlowSuccess::Ok)
                    })
                    .build()
            );

            src_pipe.set_state(gst::State::Playing).unwrap();
            
            let bus = src_pipe.bus().unwrap();
            for msg in bus.iter_timed(gst::ClockTime::NONE) {
                use gst::MessageView;
                match msg.view() {
                    MessageView::Error(e) => { println!("[NETWORK] Lost connection: {}", e.error()); break; }
                    MessageView::Eos(..) => { println!("[NETWORK] Stream ended (EOS)"); break; }
                    _ => (),
                }
            }
            let _ = src_pipe.set_state(gst::State::Null);
        }
        thread::sleep(Duration::from_millis(500));
    }
}
