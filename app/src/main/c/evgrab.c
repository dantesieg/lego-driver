// Root helper: grabs one evdev gamepad exclusively and streams its full state as text lines.
//   R <code> <min> <max> <flat>   axis range (once at start)
//   OK                            grabbed and running
//   S <a0> <a1> ... | <keys>      snapshot: current value of each axis in R order, then held key codes
// A snapshot is sent on every input frame and at least every 100 ms, so the app can treat silence
// as "controller gone" and never misses a release (state is re-read from the kernel, not tracked).
// Exits when stdin closes, the parent dies or the device goes away; the grab dies with the fd.
#include <fcntl.h>
#include <linux/input.h>
#include <poll.h>
#include <stdio.h>
#include <string.h>
#include <sys/prctl.h>
#include <signal.h>
#include <unistd.h>

static const int axes[] = {ABS_X, ABS_Y, ABS_Z, ABS_RX, ABS_RY, ABS_RZ, ABS_HAT0X, ABS_HAT0Y, ABS_GAS, ABS_BRAKE};
static const int keys[] = {BTN_EAST, BTN_NORTH, BTN_WEST, BTN_TL, BTN_TR, BTN_START,
                           BTN_DPAD_UP, BTN_DPAD_DOWN, BTN_DPAD_LEFT, BTN_DPAD_RIGHT};
#define N(a) (sizeof a / sizeof *a)

static int have[N(axes)];

static int snapshot(int fd) {
    printf("S");
    for (unsigned i = 0; i < N(axes); i++) {
        if (!have[i]) continue;
        struct input_absinfo a;
        if (ioctl(fd, EVIOCGABS(axes[i]), &a) < 0) return -1;
        printf(" %d", a.value);
    }
    unsigned char bits[KEY_MAX / 8 + 1];
    memset(bits, 0, sizeof bits);
    if (ioctl(fd, EVIOCGKEY(sizeof bits), bits) < 0) return -1;
    printf(" |");
    for (unsigned i = 0; i < N(keys); i++)
        if (bits[keys[i] / 8] & (1 << (keys[i] % 8))) printf(" %d", keys[i]);
    printf("\n");
    return fflush(stdout) == 0 ? 0 : -1;
}

int main(int argc, char **argv) {
    if (argc != 2) return 2;
    prctl(PR_SET_PDEATHSIG, SIGKILL);
    signal(SIGPIPE, SIG_DFL);
    int fd = open(argv[1], O_RDONLY | O_NONBLOCK);
    if (fd < 0) { perror("open"); return 1; }
    if (ioctl(fd, EVIOCGRAB, 1) < 0) { perror("grab"); return 1; }
    for (unsigned i = 0; i < N(axes); i++) {
        struct input_absinfo a;
        if (ioctl(fd, EVIOCGABS(axes[i]), &a) == 0 && a.maximum > a.minimum) {
            have[i] = 1;
            printf("R %d %d %d %d\n", axes[i], a.minimum, a.maximum, a.flat);
        }
    }
    printf("OK\n");
    struct pollfd p[2] = {{fd, POLLIN, 0}, {0, POLLIN, 0}};
    struct input_event ev[64];
    for (;;) {
        if (snapshot(fd) < 0) break;
        if (poll(p, 2, 100) < 0) break;
        if (p[1].revents) break;                       // stdin closed: app is done
        if (p[0].revents & (POLLERR | POLLHUP | POLLNVAL)) break;
        if (getppid() == 1) break;                     // orphaned
        // Drain; the snapshot above re-reads true state, so dropped events cannot stick.
        while (read(fd, ev, sizeof ev) > 0) {}
    }
    return 0;
}
