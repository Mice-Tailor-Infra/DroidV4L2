use clap::Parser;

#[derive(Parser, Debug, Clone)]
#[command(author, version, about, long_about = None)]
pub struct Args {
    #[arg(short = '4', long, default_value_t = 5000)]
    pub port_h264: u16,
    #[arg(short = '5', long, default_value_t = 5001)]
    pub port_h265: u16,
    #[arg(short, long, default_value = "/dev/video10")]
    pub device: String,

    /// Optional MJPEG Stream URL (e.g., http://192.168.1.5:8080/)
    #[arg(long)]
    pub mjpeg: Option<String>,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_args_default() {
        let args = Args::parse_from(["app"]);
        assert_eq!(args.port_h264, 5000);
        assert_eq!(args.port_h265, 5001);
        assert_eq!(args.device, "/dev/video10");
        assert!(args.mjpeg.is_none());
    }

    #[test]
    fn test_args_custom() {
        let args = Args::parse_from([
            "app",
            "-4",
            "6000",
            "-5",
            "6001",
            "--device",
            "/dev/video20",
            "--mjpeg",
            "http://test.com",
        ]);
        assert_eq!(args.port_h264, 6000);
        assert_eq!(args.port_h265, 6001);
        assert_eq!(args.device, "/dev/video20");
        assert_eq!(args.mjpeg.unwrap(), "http://test.com");
    }
}
