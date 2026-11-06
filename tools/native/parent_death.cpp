// SPDX-License-Identifier: Apache-2.0
// CLI process lifecycle only: prevent an orphan when Android kills :inference.
#include <signal.h>
#include <sys/prctl.h>
#include <unistd.h>
__attribute__((constructor)) static void vexto_parent_death() {
    const pid_t parent = getppid();
    if (parent == 1 || prctl(PR_SET_PDEATHSIG, SIGKILL) != 0 || getppid() != parent) _exit(125);
}
