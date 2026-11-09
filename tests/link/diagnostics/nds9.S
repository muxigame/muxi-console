.syntax unified
.arm
.global _start
_start:
 ldr sp,=0x023fe000
 bl main
1: b 1b
