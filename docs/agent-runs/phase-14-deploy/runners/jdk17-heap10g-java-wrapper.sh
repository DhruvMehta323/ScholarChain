#!/bin/bash
# F14-10 heap override for forked test JVMs: replaces -Xmx2g with -Xmx10g, otherwise execs the real JDK 17 unchanged.
args=()
for a in "$@"; do if [[ "$a" == -Xmx2g ]]; then args+=(-Xmx10g); else args+=("$a"); fi; done
exec "/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home/bin/java" "${args[@]}"
