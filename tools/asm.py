#!/usr/bin/env python3
"""Simple assembler for Toy Ascend NPU instruction set."""

import sys
import re

OPCODES = {
    'NOP':    0x0,
    'HALT':   0x1,
    'LOAD':   0x2,
    'STORE':  0x3,
    'MATMUL': 0x4,
    'VECADD': 0x5,
    'RELU':   0x6,
}

BUF_SEL = {
    'L0_A': 0b00,
    'L0_B': 0b01,
    'L0_C': 0b10,
    'VEC':  0b11,
}


def parse_int(s):
    s = s.strip().rstrip(',')
    if s.startswith('0x') or s.startswith('0X'):
        return int(s, 16)
    return int(s)


def assemble_line(line):
    """Assemble a single instruction line into a 32-bit integer."""
    # Strip comments
    line = re.sub(r'[;#].*', '', line).strip()
    if not line:
        return None

    parts = line.split()
    mnemonic = parts[0].upper()

    if mnemonic not in OPCODES:
        raise ValueError(f"Unknown instruction: {mnemonic}")

    op = OPCODES[mnemonic]

    if mnemonic in ('NOP', 'HALT'):
        return op << 28

    if mnemonic in ('LOAD', 'STORE'):
        # LOAD dst_buf, reg_addr, mem_addr
        # STORE src_buf, reg_addr, mem_addr
        if len(parts) < 4:
            raise ValueError(f"{mnemonic} requires: buf, reg_addr, mem_addr")
        buf = parts[1].strip(',').upper()
        reg_addr = parse_int(parts[2])
        mem_addr = parse_int(parts[3])
        dst_sel = BUF_SEL.get(buf, 0)
        return (op << 28) | (dst_sel << 26) | ((reg_addr & 0x3F) << 20) | ((mem_addr & 0xFFFF) << 4)

    if mnemonic == 'MATMUL':
        # MATMUL (no operands needed, uses pre-loaded L0 buffers)
        return op << 28

    if mnemonic in ('VECADD', 'RELU'):
        # VECADD src1_row, src2_row, dst_row
        # RELU src1_row, 0, dst_row
        src1 = parse_int(parts[1]) if len(parts) > 1 else 0
        src2 = parse_int(parts[2]) if len(parts) > 2 else 0
        dst = parse_int(parts[3]) if len(parts) > 3 else 0
        return (op << 28) | ((src1 & 0x3F) << 22) | ((src2 & 0x3F) << 16) | ((dst & 0x3F) << 10)

    return op << 28


def assemble(source):
    """Assemble source text into list of 32-bit instruction words."""
    instructions = []
    for i, line in enumerate(source.strip().split('\n'), 1):
        try:
            instr = assemble_line(line)
            if instr is not None:
                instructions.append(instr)
        except ValueError as e:
            print(f"Error on line {i}: {e}", file=sys.stderr)
            sys.exit(1)
    return instructions


def main():
    if len(sys.argv) < 2:
        print("Usage: asm.py <input.asm> [output.hex]")
        sys.exit(1)

    with open(sys.argv[1]) as f:
        source = f.read()

    instructions = assemble(source)

    if len(sys.argv) >= 3:
        with open(sys.argv[2], 'w') as f:
            for instr in instructions:
                f.write(f"{instr:08x}\n")
    else:
        for i, instr in enumerate(instructions):
            print(f"  [{i:3d}] 0x{instr:08X}")


if __name__ == '__main__':
    main()
