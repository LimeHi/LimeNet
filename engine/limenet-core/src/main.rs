use anyhow::{Context, Result};
use limenet_core::{CoreConfig, run};
use std::{env, fs};

#[tokio::main]
async fn main() -> Result<()> {
    let mut args = env::args_os().skip(1);
    let flag = args.next().context("usage: limenet-core --config <path>")?;
    let path = args.next().context("missing config path")?;
    if flag != "--config" || args.next().is_some() {
        anyhow::bail!("usage: limenet-core --config <path>");
    }

    let raw = fs::read(&path)
        .with_context(|| format!("cannot read config {}", path.to_string_lossy()))?;
    let config: CoreConfig = serde_json::from_slice(&raw).context("invalid LimeNet core config")?;
    run(config).await
}
