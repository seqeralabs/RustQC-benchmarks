# Dataset-driven tests

The per-tool tests under `tests/rna/rustqc/` all run on one hard-coded dataset. The tests
described here read their inputs from a manifest instead, `tests/datasets.yml`, so adding a
dataset means adding a manifest entry and its upstream references, not copying test files.

Two test files are generated from the manifest, one test per dataset each:

- **Dataset matrix** (`tests/rna/matrix/matrix.nf.test`, tag `matrix`): RustQC runs once and
  every converted tool is crosschecked against the dataset's upstream references; a small
  per-tool view is snapshotted.
- **Release regression** (`tests/rna/regression/release.nf.test`, tag `release_regression`):
  the current RustQC and its last release run on the same input and their outputs are
  compared file by file. This covers RustQC where there is no upstream reference.

The per-tool tests stay until every tool has moved to the shared checks library
([below](#shared-checks-library)).

```
tests/
├── datasets.yml                    # the dataset manifest
├── fetch_testdata.py               # download the manifest inputs (CI cache, offline runs)
├── lib/
│   ├── CompareUtils.groovy         # unqualified alias of checks.CompareUtils
│   ├── datasets/                   # package `datasets`: Datasets (manifest loader), Dataset
│   └── checks/                     # package `checks`: one ToolCheck per tool, Checks, Run,
│                                   #   CompareUtils, ReleaseRegression, Refgen
├── rna/
│   ├── matrix/matrix.nf.test       # one test per dataset: crosschecks + small snapshot
│   ├── regression/release.nf.test  # one test per dataset: current vs last released RustQC
│   └── refgen/references.nf.test   # upstream references per dataset (only with RUSTQC_REFGEN)
└── workflows/rustqc_release_regression.nf   # test-only workflow: RUSTQC_RNA twice
snapshots/rna/<dataset id>/         # upstream references of each dataset (small: the original one)
```

## Datasets

All inputs come from [nf-core/test-datasets](https://github.com/nf-core/test-datasets), by URL
at a pinned commit SHA (the `sources:` block of the manifest), so their bytes cannot change
under a test. Each input also records its md5.

| Dataset                   | Set   | Source (nf-core/test-datasets)                                                                          | Data                                                                    | Layout / strand      | BAM / GTF       |
| ------------------------- | ----- | ------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------- | -------------------- | --------------- |
| `hs_chr6_pe_rev`          | small | `modules`: `homo_sapiens/illumina/bam/test.rna.paired_end.sorted.chr6.bam`; GTF in-repo (`chr6.gtf.gz`) | Human GRCh37 chr6, Picard duplicate-marked (the original small dataset) | PE / reverse         | 3.8 MB / 1.9 MB |
| `hs_chr22_pe_fwd`         | small | `modules`: `homo_sapiens/illumina/bam/test.rna.paired_end.sorted.bam`, `homo_sapiens/genome/genome.gtf` | Human GRCh38 chr22 40 kb slice (`chr22`), 6 genes, many multi-mappers   | PE 2x150 / forward   | 0.4 MB / 15 KB  |
| `hs_chrXsub_pe_unstr`     | small | `rnasplice`: `chrX_subset/testdata/ERR188383.Aligned.out.bam`, `chrX_subset/reference/genes_chrX.gtf`   | Human GRCh37 chrX slice (GEUVADIS), GTF without gene records            | PE 2x75 / unstranded | 0.5 MB / 1.0 MB |
| `sc_pe_rev`               | small | `modules`: `saccharomyces_cerevisiae/illumina/bam/test.paired_end.sorted.bam`, `genome_gfp.gtf`         | Yeast chr I + GFP transgene (nf-core/rnaseq test data)                  | PE / reverse         | 85 KB / 0.2 MB  |
| `sc_se_rev`               | small | `modules`: `saccharomyces_cerevisiae/illumina/bam/test.single_end.sorted.bam`, `genome_gfp.gtf`         | Yeast chr I + GFP transgene (nf-core/rnaseq test data)                  | SE / reverse         | 88 KB / 0.2 MB  |
| `hs_ribo_chr19_22_se_fwd` | small | `modules`: `homo_sapiens/riboseq_expression/price/` (`SRX11780885.chr19_22.ds50.bam`, chr19+22 pc GTF)  | Human GRCh38 Ribo-seq, 20-42 nt reads, 194 contigs in the header        | SE / forward         | 1.5 MB / 1.7 MB |
| `hs_chrX_pe_unstr`        | large | `rnasplice`: `testdata/ERR188383.Aligned.out.bam`, `reference/genes_chrX.gtf`                           | Human GRCh37 full chrX (GEUVADIS), 100k records                         | PE 2x75 / unstranded | 6.4 MB / 26 MB  |
| `hs_ribo_chr20_se_fwd`    | large | `modules`: `homo_sapiens/riboseq_expression/aligned_reads/SRX11780887_chr20.bam`, Ensembl 111 chr20 GTF | Human GRCh38 Ribo-seq, full chr20, 600k records (104k secondary)        | SE / forward         | 5.8 MB / 34 MB  |

Only `hs_chr6_pe_rev` is duplicate-marked, so the other datasets run with `--skip-dup-check`.
Strandedness was checked with RSeQC `infer_experiment.py` (the references hold its output).

Known differences between RustQC and the upstream tools on these datasets (each documented
where it is handled):

- **samtools stats, GC-depth section** (`GCD` lines): percentiles differ on
  `hs_chrX_pe_unstr`, `hs_ribo_chr19_22_se_fwd` and `hs_ribo_chr20_se_fwd`; the section is
  skipped in `SamtoolsStats` for every dataset.
- **samtools stats, other lines**: on `hs_chrXsub_pe_unstr`, `COV [1-1]` is one higher than
  samtools; on `sc_pe_rev`, `SN error rate` differs in the last printed digit. Both datasets mark
  `samtools_stats: regression`.
- **dupRadar intercept/slope without duplicates**: with no read marked duplicate the logistic fit
  is degenerate; R stops at an intercept of ~2.9e-12, RustQC at ~1e-10. `DupRadar` accepts any
  value below 1e-9 in that case. The dupMatrix itself is byte-identical on every
  dataset with an upstream reference.
- **dupRadar on `hs_chr22_pe_fwd`**: upstream dupRadar exits with an error (6 genes), so the
  dataset marks `dupradar: upstream_error`.

The `small` set runs on every pull request. The `large` set runs on pushes to `main`, on
releases, on the weekly schedule and in the `large` manual mode, never on pull requests
([CI](#ci)).

## Manifest

Each entry of `datasets:` in `tests/datasets.yml` has an `id` (also the nf-test tag `ds_<id>`),
a `set` (`small` or `large`, also a tag), its `inputs` (`bam`, `bai`, `gtf`), the RustQC `meta`
(`single_end`, `strandedness`) and params, the directory of its upstream `references`, a
per-tool `expect` map and axis `tags` (`pe`/`se`, the strandedness, the assembly, ...). The
header of the file documents every key.

An input can also have a `local` copy in the repository: it is used when the input is not
hosted in nf-core yet (today: the chr6 GTF), or for every input when `RUSTQC_TESTDATA=local` is
set. Outputs that echo the BAM name (featureCounts) are normalised to `references.bam_name`
before comparing, because the nf-core file name of `hs_chr6_pe_rev` differs from the
`test.bam` its references were generated from.

`expect:` sets what each tool's crosscheck means on that dataset (default `match`):

| `expect`         | Crosscheck                                                                    |
| ---------------- | ----------------------------------------------------------------------------- |
| `match`          | compare with the upstream reference                                           |
| `regression`     | no upstream comparison (no upstream equivalent, or a documented difference)   |
| `absent`         | RustQC must not write this tool's outputs (e.g. inner_distance on single-end) |
| `upstream_error` | the upstream tool fails on this input: no reference, nothing to compare       |

In the last two cases, and for `regression`, RustQC is still covered by the snapshot and by the
release regression.

## Running

```bash
# Every dataset-driven test of the small set
nf-test test tests/rna/matrix tests/rna/regression

# The large set (or both: RUSTQC_DATASET_SETS=all)
RUSTQC_DATASET_SETS=large nf-test test --tag large

# One dataset, or one axis of the matrix
nf-test test --tag ds_sc_se_rev
nf-test test --tag se          # every test of every single-end dataset

# Only some datasets / only some tools' crosschecks while iterating
RUSTQC_DATASETS=hs_chr6_pe_rev nf-test test tests/rna/matrix
RUSTQC_CHECK_TOOLS=bam_stat,flagstat nf-test test tests/rna/matrix

# Download the inputs once and reuse them (what CI does)
python3 tests/fetch_testdata.py --out ~/.cache/rustqc-testdata --sets all
RUSTQC_TESTDATA_DIR=~/.cache/rustqc-testdata nf-test test tests/rna/matrix

# Without containers: put a rustqc binary on PATH and drop the docker profile
PATH=/path/to/rustqc/dir:$PATH nf-test test --profile test tests/rna/matrix
```

Tests are generated in a Groovy loop over the manifest (`Datasets.ofClass('content').each { ds
-> test(ds.id) { ... } }`), checked with nf-test 0.9.5. `nf-test list` does not load `libDir`
and fails on these files with "unable to resolve class"; use `nf-test test --dry-run` to list
them.

| Variable                | Effect                                                                       |
| ----------------------- | ---------------------------------------------------------------------------- |
| `RUSTQC_DATASET_SETS`   | sets to generate tests for: `small` (default), `large`, `small,large`, `all` |
| `RUSTQC_DATASETS`       | only these dataset ids (comma-separated)                                     |
| `RUSTQC_CHECK_TOOLS`    | only crosscheck these tools                                                  |
| `RUSTQC_TESTDATA_DIR`   | directory of pre-downloaded inputs (see [Test data cache](#test-data-cache)) |
| `RUSTQC_TESTDATA=local` | use the in-repository `local` copies                                         |
| `RUSTQC_BASELINE_IMAGE` | release-regression baseline image                                            |
| `RUSTQC_REFGEN`         | generate the upstream-reference tests                                        |

## Shared checks library

`tests/lib/checks/` holds the assertions that used to live in the `then` blocks of
`tests/rna/rustqc/<tool>.nf.test`, one `ToolCheck` subclass per tool:

- `crosscheck(run)`: compare RustQC with the dataset's upstream reference
- `regressionView(run)`: a small deterministic view for `snapshot()`: full text of small files,
  md5 + row count for large tables, so the matrix snapshot stays a few KB per dataset
- `references`: the reference files `crosscheck` reads, and where the pipeline publishes the
  upstream output each one is copied from (used to generate references)

`Checks.crosscheck(ds, outDir)` runs every registered tool, honouring `expect:`, and reports
all failing tools together. Converted so far: `bam_stat`, `infer_experiment`, `flagstat`,
`idxstats`, `samtools_stats`, `featurecounts_geneid`, `read_distribution`, `read_duplication`,
`junction_annotation` (BED and XLS) and `dupradar`. Still only in the per-tool tests:
`featurecounts` (biotypes), `inner_distance`, `junction_saturation`, `preseq`, `qualimap` and
`tin`. To convert one, move its assertions into a new `ToolCheck` subclass, register it in
`Checks.TOOLS`, take its upstream process out of the skip list in
`tests/rna/refgen/refgen.config`, and regenerate the references of every dataset
([Upstream references](#upstream-references)).

## Release regression

Where there is no upstream reference (an upstream tool that fails on an input, or a tool with
no upstream equivalent), RustQC is checked against **its own last release** instead.
`tests/rna/regression/release.nf.test` runs `RUSTQC_RNA` twice per dataset with identical inputs
and arguments: once with the current image (`params.rustqc_image`,
`ghcr.io/seqeralabs/rustqc:dev` by default) and once with `release_regression.baseline_image`
from `tests/datasets.yml` (override with `RUSTQC_BASELINE_IMAGE`). It then compares the two
`rustqc/` trees:

- every file exactly, except plots (`*.png`, `*.svg`, `*.pdf`) and `CITATIONS.md`, and after
  dropping version/command-line header lines (featureCounts `# Program:`, the samtools stats
  header) and the Qualimap report's run timestamps. Qualimap junction motifs with equal
  percentages are compared as a set, because RustQC lists them in a random order (a known RustQC
  bug). The exclusions are listed in `tests/lib/checks/ReleaseRegression.groovy`;
- a file the baseline writes but the current run does not fails; a new file is only reported;
- intentional changes since the release go in `release_regression.accepted_differences`
  (`{path: <glob under rustqc/>, reason: ..., datasets: [optional ids]}`): reported, not failed.

It is tagged `release_regression` (not `regression`, which is the snapshot suite and needs no
extra image) and needs a container engine for the two images. Without one, run it on two local
binaries (release binaries are on the RustQC GitHub releases page):

```bash
RUSTQC_BASELINE_BIN=/path/to/v0.2.1/rustqc RUSTQC_CURRENT_BIN=/path/to/new/rustqc \
    nf-test test --profile test --tag release_regression
```

The test fails if both runs used the same binary (identical `RustQC vX (commit, built ...)`
banners), so it cannot pass by comparing a build with itself.

**After each RustQC release:** set `baseline_image` to the new tag (list tags with
`gh api /orgs/seqeralabs/packages/container/rustqc/versions`) and empty `accepted_differences`.

## Upstream references

A dataset's references are the upstream tools' outputs on it, in `references.dir`, under the
names the checks read. They are generated, not written by hand:
`tests/rna/refgen/references.nf.test` runs the pipeline with `--run_upstream` (no RustQC) on
each dataset and `checks.Refgen` copies the outputs of every converted tool into place. An
upstream tool that fails is skipped and reported; mark it `upstream_error` in `expect:`. The
upstream tools no converted check reads yet (TIN, Qualimap, ...) are not run
(`tests/rna/refgen/refgen.config`).

The file generates no tests unless `RUSTQC_REFGEN` is set:

```bash
# Write into snapshots/rna/<id>/ in the working tree
RUSTQC_REFGEN=1 RUSTQC_DATASETS=sc_se_rev nf-test test --tag refgen
RUSTQC_REFGEN=1 RUSTQC_DATASET_SETS=all nf-test test --tag refgen   # every dataset
```

On GitHub, run the nf-test workflow manually with test mode `refgen` (optionally with
`datasets`): it uploads the references as the `upstream-references` artifact, laid out as in
the repository. Unpack it at the repository root and review the diff.

## Adding a dataset

1. Add an entry under `datasets:` in `tests/datasets.yml`: pinned nf-core URLs and the `md5` of
   each input, `set`, `meta` (check the strandedness with RSeQC `infer_experiment.py`),
   `references.dir: snapshots/rna/<id>`, `tags`. Keep `small` datasets to a few MB; anything
   slower goes in `large`.
2. Generate its references (`refgen` mode, above) and commit them. Set `expect:` for tools whose
   upstream run failed (`upstream_error`) or that RustQC does not write (`absent`).
3. Run `nf-test test --tag ds_<id> --update-snapshot` (with `RUSTQC_DATASET_SETS=large` for a
   large dataset). A crosscheck failure is a RustQC/upstream difference: fix RustQC, or document
   it next to an `expect: {<tool>: regression}` entry. Review the new snapshot entry and commit.

## CI

`.github/workflows/nf-test.yml` runs the tests:

| Event                           | Small set (all tests)                     | Large set    |
| ------------------------------- | ----------------------------------------- | ------------ |
| pull request                    | tests related to the change, all profiles | no           |
| push to `main`                  | no (ran on the pull request)              | yes          |
| release                         | every test                                | yes          |
| weekly schedule (Mon 04:30 UTC) | every test                                | yes          |
| manual (`workflow_dispatch`)    | by `test_mode`                            | `large` mode |

Manual modes (`test_mode`): `all` (every small-set test), `regression`, `crosscheck`,
`upstream`, `matrix`, `release_regression` (each selects that tag), `large` (only the large
set) and `refgen` (generate upstream references, see above). The `datasets` input narrows the
dataset-driven tests to some dataset ids.

The conda jobs only run the `upstream` tests, because the RustQC module is container-only. The
manifest refuses the tags `upstream` and `refgen` (and the set names) as dataset tags, so a
dataset can never pull RustQC tests into those jobs.

### Test data cache

A `download-testdata` job downloads the inputs of each set that will run
(`tests/fetch_testdata.py`, md5-checked) into `.testdata/` and saves it with `actions/cache`,
keyed on the set, `TESTDATA_CACHE_VERSION` and the hash of `tests/datasets.yml`; on a cache hit
it downloads nothing. The test jobs restore the same key and set `RUSTQC_TESTDATA_DIR`, which
`Datasets` uses for every input whose copy is there with the manifest md5. Anything else falls
back to the nf-core URL, so a failed download job or a cache miss only makes the tests slower.

GitHub-hosted runners limit `actions/cache` to 10 GB per repository (older entries are evicted
first), which is enough for the manifest's inputs. A cache miss only costs the download.
