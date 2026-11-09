.syntax unified
.arm
.global _start
_start:
 ldr sp,=0x0380f000
 bl main
1: b 1b
