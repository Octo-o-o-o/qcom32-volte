/*
 * Copyright (C) 2012 The Android Open Source Project
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 *  * Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 *  * Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in
 *    the documentation and/or other materials provided with the
 *    distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS
 * FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
 * COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT,
 * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING,
 * BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS
 * OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED
 * AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT
 * OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF
 * SUCH DAMAGE.
 */

/* Minimal ARM32 dynamic entry adapted from android-11.0.0_r48 crtbegin.c.
 * No C++/TLS globals or application constructors in this diagnostic executable. */
typedef void (*ctor)(void);
/* Zig's no-libc driver omits PT_INTERP even with --dynamic-linker; supply the
 * ELF section explicitly and verify PT_INTERP in the build before execution. */
__attribute__((section(".interp"),used)) const char passport_interpreter[] = "/system/bin/linker";
typedef struct { ctor *preinit_array; ctor *init_array; ctor *fini_array; } structors_array_t;
extern int main(int, char **, char **);
extern void __libc_init(void *, void *, int (*)(int,char **,char **), const structors_array_t *) __attribute__((noreturn));
__attribute__((section(".preinit_array"))) static ctor pre[2] = {(ctor)-1, 0};
__attribute__((section(".init_array"))) static ctor init[2] = {(ctor)-1, 0};
__attribute__((section(".fini_array"))) static ctor fini[2] = {(ctor)-1, 0};
__attribute__((used)) static void _start_main(void *raw_args) {
    structors_array_t arrays = {pre,init,fini};
    __libc_init(raw_args,0,main,&arrays);
}
__asm__(".text; .global _start; .type _start,%function; _start: mov r0,sp; b _start_main; .size _start, .-_start");
__asm__(".section .tdata,\"awT\",%progbits\n .p2align 5\n .text\n .reloc 0, R_ARM_NONE, .tdata\n");
