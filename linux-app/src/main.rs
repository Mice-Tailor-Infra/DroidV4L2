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
    new_data_arrived: bool,
}

fn main() -> Result<()> {
    let args = Args::parse();
    gst::init().context("GStreamer init failed")?;

    let state = Arc::new(Mutex::new(SharedState {
        last_sample: None,
        new_data_arrived: false,
    }));

    // --- 1. 启动持久化输出管线 (Output Pipeline) ---
    // 这个管线占住 V4L2 设备，永不关闭
    let sink_pipeline_str = format!(
        "appsrc name=mysrc is-live=true format=time ! videoconvert ! video/x-raw,format=YUY2 ! v4l2sink device={} sync=false",
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
    println!(">>> Permanent V4L2 Sink Pipeline Started on {} <<<", args.device);

    // --- 2. 启动数据注入线程 (Keep-alive Thread) ---
    // 负责把最新帧（或最后一帧）持续喂给 appsrc
    let state_clone = Arc::clone(&state);
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
                    let mut new_buffer_ref = new_buffer.get_mut().unwrap();
                    new_buffer_ref.set_pts(gst::ClockTime::from_nseconds(timestamp));
                    new_buffer_ref.set_duration(gst::ClockTime::from_nseconds(33_333_333)); // ~30fps
                    
                    // 拷贝数据
                    let map = buffer.map_readable().unwrap();
                    let mut new_map = new_buffer_ref.map_writable().unwrap();
                    new_map.copy_from_slice(&map);
                }
                
                let _ = appsrc_clone.push_buffer(new_buffer);
                timestamp += 33_333_333;
            }
            
            // 保持约 30fps 的注入频率
            thread::sleep(Duration::from_millis(33));
        }
    });

    // --- 3. 动态拉流管线循环 (Input Pipeline Loop) ---
    loop {
        println!(">>> Listening for Android SRT stream on port {}...", args.port);
        let src_pipeline_str = format!(
            "srtsrc uri=srt://:{}?mode=listener&latency=20&streamid=live ! tsdemux ! h264parse ! avdec_h264 ! appsink name=mysink sync=false",
            args.port
        );

        if let Ok(src_pipeline) = gst::parse_launch(&src_pipeline_str) {
            let appsink = src_pipeline
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
                        s.last_sample = Some(sample);
                        s.new_data_arrived = true;
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
                        println!("Input stream error: {}. Restarting listener...", err.error());
                        break;
                    }
                    MessageView::Eos(..) => {
                        println!("Input stream EOS. Restarting listener...");
                        break;
                    }
                    _ => (),
                }
            }
            src_pipeline.set_state(gst::State::Null).unwrap();
        }
        thread::sleep(Duration::from_millis(500));
    }
}
