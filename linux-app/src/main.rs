use anyhow::{Result, Context};
use clap::Parser;
use gstreamer as gst;
use gst::prelude::*;

#[derive(Parser, Debug)]
#[command(author, version, about, long_about = None)]
struct Args {
    #[arg(short, long, default_value_t = 5000)]
    port: u16,
    #[arg(short, long, default_value = "/dev/video10")]
    device: String,
}

fn main() -> Result<()> {
    let args = Args::parse();
    gst::init().context("Failed to initialize GStreamer")?;

    // 显式指定接收 MPEG-TS 格式，这是解决 not-negotiated 的关键
    let pipeline_str = format!(
        "srtsrc uri=srt://:{}?mode=listener&latency=50&streamid=live ! tsdemux ! h264parse ! avdec_h264 ! videoconvert ! video/x-raw,format=YUY2 ! v4l2sink device={}",
        args.port, args.device
    );

    println!("Starting SRT Bridge...");
    println!("Pipeline: {}", pipeline_str);

    let pipeline = gst::parse_launch(&pipeline_str).context("Failed to parse pipeline")?;
    pipeline.set_state(gst::State::Playing).context("Failed to set Playing state")?;

    let bus = pipeline.bus().expect("Pipeline without bus");
    println!("Waiting for Android data...");

    for msg in bus.iter_timed(gst::ClockTime::NONE) {
        use gst::MessageView;
        match msg.view() {
            MessageView::Error(err) => {
                let src_name = msg.src().map(|s| s.path_string()).unwrap_or_else(|| "unknown".into());
                eprintln!("ERROR from {}: {} ({:?})", src_name, err.error(), err.debug());
                break;
            }
            MessageView::StateChanged(s) => {
                if let Some(src) = msg.src() {
                    if src.has_pipeline(&pipeline) {
                        println!("Pipeline state: {:?} -> {:?}", s.old(), s.current());
                    }
                }
            }
            _ => (),
        }
    }

    pipeline.set_state(gst::State::Null)?;
    Ok(())
}

trait GstObjExt {
    fn has_pipeline(&self, pipeline: &gst::Element) -> bool;
}

impl GstObjExt for gst::Object {
    fn has_pipeline(&self, pipeline: &gst::Element) -> bool {
        self.downcast_ref::<gst::Element>().map(|e| e == pipeline).unwrap_or(false)
    }
}