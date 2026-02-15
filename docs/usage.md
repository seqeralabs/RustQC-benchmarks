# Usage

See the main [README](../README.md) for quick-start commands.

## Running nf-test validation

The primary way to use this repo is through nf-test. The `nf-test.config` already configures the `test,docker` profiles, so Docker containers are pulled automatically.

### Prerequisites

- [Nextflow](https://www.nextflow.io/) >= 25.04.0
- [nf-test](https://www.nf-test.com) >= 0.9.2
- Docker running locally

### Run all tests

```bash
nf-test test tests/rna/upstream/ tests/rna/rustqc/
```

### Run by tag

```bash
nf-test test --tag upstream      # upstream reference tools only
nf-test test --tag rustqc        # RustQC comparison tests only
nf-test test --tag bam_stat      # single tool (both upstream + rustqc)
nf-test test --tag rna           # everything
```

### Update snapshots

After intentional changes to RustQC or upstream tools:

```bash
nf-test test --tag rustqc --update-snapshot
nf-test test --tag upstream --update-snapshot
```

Review the diff in `.nf.test.snap` files before committing.

## Running the Nextflow pipeline

The pipeline can be run directly for benchmarking on larger datasets or on Seqera Platform.

### Small test dataset (local)

```bash
# RustQC only (default)
nextflow run main.nf -profile rna_test,docker

# Upstream tools only
nextflow run main.nf -profile rna_test,docker --run_upstream --run_rustqc false

# Both
nextflow run main.nf -profile rna_test,docker --run_upstream
```

### Custom data

```bash
nextflow run main.nf -profile docker \
    --bam /path/to/sample.bam \
    --bai /path/to/sample.bam.bai \
    --gtf /path/to/annotation.gtf \
    --bed /path/to/genes.bed \
    --sample_id my_sample \
    --paired true \
    --strandedness reverse \
    --outdir results/my_run
```

### On Seqera Platform

```bash
nextflow run main.nf -profile rna_test,docker \
    --run_upstream \
    --outdir s3://your-bucket/rustqc-benchmarks
```

The Nextflow trace report provides CPU time and memory usage for each process -- useful for performance comparison.

## Using a different RustQC build

Override the Docker image:

```bash
nextflow run main.nf -profile rna_test,docker \
    --rustqc_image ghcr.io/ewels/rustqc:latest
```

Or use a local binary (bypasses Docker for RustQC only):

```bash
nextflow run main.nf -profile rna_test,docker \
    --rustqc_image '' \
    --rustqc_binary /path/to/rustqc
```
