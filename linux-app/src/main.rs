use anyhow::{Result, Context};
use clap::Parser;
use gstreamer as gst;
use gstreamer_app as gst_app;
use gst::prelude::*;
use std::sync::{Arc, Mutex};
use std::time::Duration;
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
}

fn main() -> Result<()> {
    let args = Args::parse();
    gst::init().context("GStreamer init failed")?;

    let shared_state = Arc::new(Mutex::new(SharedState { last_sample: None }));

    // --- 1. 持久 Sink 管线 (保持 V4L2 永远在线) ---
    let sink_pipeline_str = format!(
        "appsrc name=mysrc is-live=true format=time ! video/x-raw,format=I420,width=1280,height=720,framerate=30/1 ! videoconvert ! video/x-raw,format=YUY2 ! v4l2sink device={} sync=false",
        args.device
    );
    let sink_pipeline = gst::parse_launch(&sink_pipeline_str)?;
    let appsrc = sink_pipeline
        .downcast_ref::<gst::Bin>().unwrap()
        .by_name("mysrc").unwrap()
        .downcast::<gst_app::AppSrc>().unwrap();

    sink_pipeline.set_state(gst::State::Playing)?;
    println!(">>> Permanent V4L2 Sink Active on {} <<<", args.device);

    // --- 2. 恒定保活线程 ---
    let state_clone = Arc::clone(&shared_state);
    let appsrc_clone = appsrc.clone();
    thread::spawn(move || {
        let mut timestamp = 0;
        loop {
            let sample = {
                let s = state_clone.lock().unwrap();
                s.last_sample.clone()
            };

            if let Some(sample) = sample {
                let buffer = sample.buffer().unwrap();
                let mut new_buffer = gst::Buffer::with_size(buffer.size()).unwrap();
                {
                    let mut b = new_buffer.get_mut().unwrap();
                    b.set_pts(gst::ClockTime::from_nseconds(timestamp));
                    b.set_duration(gst::ClockTime::from_nseconds(33_333_333));
                    
                    let map = buffer.map_readable().unwrap();
                    let mut new_map = b.map_writable().unwrap();
                    new_map.copy_from_slice(&map);
                }
                let _ = appsrc_clone.push_buffer(new_buffer);
                timestamp += 33_333_333;
            }
            thread::sleep(Duration::from_millis(33));
        }
    });

    // --- 3. 极速重连 Source 循环 ---
    loop {
        // 使用更短的 poll-timeout (500ms) 让重启更敏捷
        let src_pipeline_str = format!(
            "srtsrc uri=srt://:{}?mode=listener&latency=20&streamid=live&poll-timeout=500 ! tsdemux ! h264parse ! avdec_h264 ! videoconvert ! video/x-raw,format=I420 ! appsink name=mysink sync=false",
            args.port
        );

        if let Ok(src_pipeline) = gst::parse_launch(&src_pipeline_str) {
            let appsink = src_pipeline
                .downcast_ref::<gst::Bin>().unwrap()
                .by_name("mysink").unwrap()
                .downcast::<gst_app::AppSink>().unwrap();

            let state_input = Arc::clone(&shared_state);
            appsink.set_callbacks(
                gst_app::AppSinkCallbacks::builder()
                    .new_sample(move |sink| {
                        let sample = sink.pull_sample().map_err(|_| gst::FlowError::Error)?;
                        let mut s = state_input.lock().unwrap();
                        s.last_sample = Some(sample);
                        Ok(gst::FlowSuccess::Ok)
                    })
                    .build(),
            );

            src_pipeline.set_state(gst::State::Playing).unwrap();

            let bus = src_pipeline.bus().expect("No bus");
            for msg in bus.iter_timed(gst::ClockTime::NONE) {
                use gst::MessageView;
                match msg.view() {
                    MessageView::Error(err) => {
                        println!("Input Pipe Error: {}. Restarting listener...", err.error());
                        break;
                    }
                    MessageView::Eos(..) => {
                        println!("Input Pipe EOS. Restarting listener...");
                        break;
                    }
                    _ => (),
                }
            }
            src_pipeline.set_state(gst::State::Null).unwrap();
        }
        thread::sleep(Duration::from_millis(100)); // 极速重试
    }
}