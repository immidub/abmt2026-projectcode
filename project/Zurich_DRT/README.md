# Zürich DRT experiments

The configs use the inputs in `project/Zurich_10pct` within this repository.
This includes the network, households, transit files, fare data, calibrated
conventional mode-choice parameters, and Canton Zürich boundary. The historical
baseline outputs are unnecessary for these runs.

The population file exceeds GitHub's regular Git file-size limit. Share
`zurich_population.xml.gz` separately and place it at
`project/Zurich_10pct/zurich_population.xml.gz` before running. Git ignores
that file and local run outputs in `project/Zurich_DRT/outputs`.

## Run from a space-free staging directory

From the repository root, with Java 25 and the standalone Switzerland JAR
already built, run the following. Use `model=erath` to select Erath.

```sh
(
  set -eu
  repo_dir="$PWD"
  model=kagho
  stage="$(mktemp -d /private/tmp/eqasim-zurich-drt.XXXXXX)"
  drt_dir="project/Zurich_DRT"
  inputs_dir="project/Zurich_10pct"
  test -r "$repo_dir/$inputs_dir/zurich_population.xml.gz"
  mkdir -p "$stage/$drt_dir" "$repo_dir/$drt_dir/outputs"
  ln -s "$repo_dir/$inputs_dir" "$stage/$inputs_dir"
  cp -R "$repo_dir/$drt_dir/configs" "$repo_dir/$drt_dir/fleets" \
    "$stage/$drt_dir/"
  run_dir="$drt_dir/outputs"
  ln -s "$repo_dir/$drt_dir/outputs" "$stage/$run_dir"
  config="$stage/$drt_dir/configs/zurich_drt_${model}.xml"
  sed -i '' -E "s|(name=\"outputDirectory\" value=\")[^\"]*|\1$run_dir/${stage##*/}-$model|" "$config"
  cd "$stage"
  java -Xmx8g -cp "$repo_dir/switzerland/target/switzerland-2.0.0.jar" \
    org.eqasim.switzerland.ch_cmdp.RunSimulation --config-path "$config"
)
```

The staged config uses a unique output directory and retains
`failIfDirectoryExists`. Run from the staging root because MATSim interprets
the relative output directory from the process working directory.
