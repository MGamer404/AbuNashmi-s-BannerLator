cmake_minimum_required(VERSION 3.22.1)

project(PRoot)

# _GNU_SOURCE is not optional here. bionic guards process_vm_readv, process_vm_writev and pipe2
# behind __USE_GNU, which only _GNU_SOURCE defines, and proot reads and writes the traced process's
# memory through the first two. Without it they compile as undeclared functions, which C assumes
# return int - so on a 64-bit target their results are truncated and every exec through proot dies
# with "Bad address". minSdkVersion 26 clears the API 23 guard on those symbols.
#
# An implicit declaration is therefore an error, not a warning: it is how this broke silently once.
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O2 -Wno-unused-function -D_GNU_SOURCE -Werror=implicit-function-declaration")

add_library(talloc STATIC
            talloc/talloc.c)

include_directories(src
                    talloc)

add_executable(libproot.so
               src/cli/cli.c
               src/cli/proot.c
               src/cli/note.c
               src/execve/enter.c
               src/execve/exit.c
               src/execve/elf.c
               src/execve/auxv.c
               src/path/binding.c
               src/path/glue.c
               src/path/canon.c
               src/path/path.c
               src/path/proc.c
               src/path/temp.c
               src/syscall/seccomp.c
               src/syscall/syscall.c
               src/syscall/chain.c
               src/syscall/enter.c
               src/syscall/exit.c
               src/syscall/sysnum.c
               src/syscall/socket.c
               src/syscall/heap.c
               src/syscall/rlimit.c
               src/tracee/tracee.c
               src/tracee/mem.c
               src/tracee/reg.c
               src/tracee/event.c
               src/tracee/seccomp.c
               src/ptrace/ptrace.c
               src/ptrace/wait.c)

target_link_libraries(libproot.so
                      talloc)

# The loader is a freestanding flat binary proot execs at LOADER_ADDRESS in place of every
# program; it is named like a library so the installer places it beside libproot.so.
add_executable(libproot-loader.so
               src/loader/loader.c)
target_compile_options(libproot-loader.so PRIVATE -ffreestanding -fno-stack-protector -fno-pic -fno-pie)
target_link_options(libproot-loader.so PRIVATE -static -nostdlib -no-pie -Wl,-e,_start
                    -Wl,--image-base=0x2000000000 -Wl,-z,noexecstack)