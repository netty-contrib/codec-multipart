#!/bin/bash

#
# Under linux, you need to do this (see https://github.com/jvm-profiling-tools/async-profiler):
#
# sudo sysctl kernel.perf_event_paranoid=1
# sudo sysctl kernel.kptr_restrict=0
#

EVENT=${1:cpu}
CLASS=${2}
ASYNCPROFILER=/Users/pderop/Work/projects/async-profiler
java -jar target/microbenchmarks.jar -f 1 -i 2 -wi 2 -r 2 -w 2 -prof async:libPath=$ASYNCPROFILER/build/libasyncProfiler.so\;event=${EVENT} ${CLASS}





