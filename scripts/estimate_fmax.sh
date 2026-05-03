#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT_DIR="${ROOT_DIR}/generated/sta/fmax"

LIB_ROOT="${LIB_ROOT:-/root/.volare/volare/sky130/versions/0fe599b2afb6708d281543108caf8310912f54af/sky130A/libs.ref/sky130_fd_sc_hd/lib}"
LIB_TT="${LIB_TT:-${LIB_ROOT}/sky130_fd_sc_hd__tt_025C_1v80.lib}"
LIB_SS="${LIB_SS:-${LIB_ROOT}/sky130_fd_sc_hd__ss_100C_1v60.lib}"
PERIOD_NS="${PERIOD_NS:-1000}"

DEFAULT_MODULES=(
  AiCore
  ControlCpu
  CubeCore
  SystolicArray
  VectorCore
  Mte2
  ScalarUnit
  SM
  SMSubPartition
  InstructionDispatcher
  CudaCore
  SFU
  SharedRegisterFile
)

modules=("$@")
if [ "${#modules[@]}" -eq 0 ]; then
  modules=("${DEFAULT_MODULES[@]}")
fi

mkdir -p "${OUT_DIR}"

cd "${ROOT_DIR}"
sbt "runMain top.Elaborate" >/dev/null

csv="${OUT_DIR}/fmax_summary.csv"
report_csv="${REPORT_CSV:-${ROOT_DIR}/docs/frequency_fmax_summary.csv}"
echo "module,arch,corner,critical_path_ns,fmax_mhz,worst_slack_ns" | tee "${csv}" > "${report_csv}"

run_sta() {
  local tcl="$1"
  local log="$2"

  if command -v sta >/dev/null 2>&1; then
    sta "${tcl}" > "${log}"
  else
    (cd /opt/openlane2 && /root/.nix-profile/bin/nix-shell --run "sta ${tcl}") > "${log}"
  fi
}

for top in "${modules[@]}"; do
  case "${top}" in
    ToyGpuTop|SM|SMSubPartition|InstructionDispatcher|CudaCore|SFU|SharedRegisterFile|WarpScheduler|CTAScheduler)
      arch="gpu"
      sv_glob="${ROOT_DIR}/generated/gpu/yosys/*.sv"
      ;;
    *)
      arch="npu"
      sv_glob="${ROOT_DIR}/generated/ascend/yosys/*.sv"
      ;;
  esac

  mapped="${OUT_DIR}/${top}_mapped.v"
  yosys_log="${OUT_DIR}/${top}_yosys.log"
  sta_tcl="${OUT_DIR}/${top}_sta.tcl"
  sta_log="${OUT_DIR}/${top}_sta.log"

  yosys -ql "${yosys_log}" -p "
    read_verilog -sv ${sv_glob}
    hierarchy -top ${top}
    proc; opt; flatten; opt
    memory_map; techmap; opt
    dfflibmap -liberty ${LIB_TT}
    abc -liberty ${LIB_TT}
    clean
    write_verilog -noattr ${mapped}
  "

  cat > "${sta_tcl}" <<EOF
read_liberty ${LIB_SS}
read_verilog ${mapped}
link_design ${top}
create_clock -name clk -period ${PERIOD_NS} [get_ports clock]
if {[llength [get_ports reset]] > 0} {
  set_case_analysis 0 [get_ports reset]
}
set_input_delay 0 -clock clk [all_inputs -no_clocks]
set_output_delay 0 -clock clk [all_outputs]
report_worst_slack -max
report_checks -path_delay max -group_count 1 -digits 3
EOF

  run_sta "${sta_tcl}" "${sta_log}"

  arrival="$(awk '/data arrival time/ && ($1 + 0) > 0 {v=$1} END {print v}' "${sta_log}")"
  slack="$(awk '/worst slack/ {v=$NF} END {print v}' "${sta_log}")"
  fmax="$(awk -v a="${arrival}" 'BEGIN { if ((a + 0) > 0) printf "%.2f", 1000.0 / a; else printf "nan" }')"

  echo "${top},${arch},sky130_ss_100C_1v60,${arrival},${fmax},${slack}" | tee -a "${csv}" "${report_csv}"
done

echo "Wrote ${csv}"
echo "Wrote ${report_csv}"
