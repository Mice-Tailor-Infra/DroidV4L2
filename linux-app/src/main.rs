use anyhow::{Result, Context};
use clap::Parser;
use gstreamer as gst;
use gst::prelude::*;
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

fn run_pipeline(args: &Args) -> Result<()> {
    // 每次启动都重新构建 pipeline
    let pipeline_str = format!(
        "srtsrc uri=srt://:{}?mode=listener&latency=20&streamid=live ! \
         tsdemux ! h264parse ! avdec_h264 ! \
         queue max-size-buffers=1 leaky=downstream ! \
         videoconvert ! video/x-raw,format=YUY2 ! \
         v4l2sink device={} sync=false",
        args.port, args.device
    );

    println!("Pipeline building: {}", pipeline_str);
    let pipeline = gst::parse_launch(&pipeline_str).context("Failed to parse pipeline")?;
    pipeline.set_state(gst::State::Playing).context("Failed to set Playing state")?;

    let bus = pipeline.bus().expect("Pipeline without bus");
    println!("Waiting for connection or data...");

    for msg in bus.iter_timed(gst::ClockTime::NONE) {
        use gst::MessageView;
        match msg.view() {
            MessageView::Error(err) => {
                let src_name = msg.src().map(|s| s.path_string()).unwrap_or_else(|| "unknown".into());
                eprintln!("Pipeline Error from {}: {}. Restarting...", src_name, err.error());
                break; // 跳出内部循环，触发重启
            }
            MessageView::Eos(..) => {
                println!("End of stream. Restarting...");
                break;
            }
            MessageView::StateChanged(s) if msg.src().map(|src| src.has_pipeline(&pipeline)).unwrap_or(false) => {
                if s.current() == gst::State::Playing {
                    println!("--- STREAM ACTIVE ---");
                }
            }
            _ => (),
        }
    }

    pipeline.set_state(gst::State::Null)?;
    Ok(())
}

fn main() -> Result<()> {
    let args = Args::parse();
    gst::init().context("Failed to initialize GStreamer")?;

    println!("DroidV4L2 Bridge Started.");
    
    // 无限重启循环
    loop {
        if let Err(e) = run_pipeline(&args) {
            eprintln!("Pipeline execution failed: {}. Retrying in 1s...", e);
            thread::sleep(Duration::from_secs(1));
        }
    }
}

trait GstObjExt {
    fn has_pipeline(&self, pipeline: &gst::Element) -> bool;
}

impl GstObjExt for gst::Object {
    fn has_pipeline(&self, pipeline: &gst::Element) -> bool {
        self.downcast_ref::<gst::Element>().map(|e| e == pipeline).unwrap_or(false)
    }
}