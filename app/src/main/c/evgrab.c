// Root helper: grabs one evdev gamepad exclusively and prints its events as text lines.
//   R <code> <min> <max> <flat>   axis range (printed once at start)
//   A <code> <value>              EV_ABS
//   K <code> <value>              EV_KEY (1 down, 0 up)
// Exits when stdin closes, so the app's death releases the grab.
#include <fcntl.h>
#include <linux/input.h>
#include <poll.h>
#include <stdio.h>
#include <unistd.h>

int main(int argc, char **argv) {
    if (argc != 2) return 2;
    int fd = open(argv[1], O_RDONLY);
    if (fd < 0) { perror("open"); return 1; }
    if (ioctl(fd, EVIOCGRAB, 1) < 0) perror("grab");
    static const int axes[] = {ABS_X, ABS_Y, ABS_Z, ABS_RX, ABS_RY, ABS_RZ, ABS_HAT0X, ABS_HAT0Y, ABS_GAS, ABS_BRAKE};
    for (unsigned i = 0; i < sizeof axes / sizeof *axes; i++) {
        struct input_absinfo a;
        if (ioctl(fd, EVIOCGABS(axes[i]), &a) == 0 && a.maximum > a.minimum)
            printf("R %d %d %d %d\n", axes[i], a.minimum, a.maximum, a.flat);
    }
    printf("OK\n");
    fflush(stdout);
    struct pollfd p[2] = {{fd, POLLIN, 0}, {0, POLLIN, 0}};
    struct input_event ev[32];
    for (;;) {
        if (poll(p, 2, -1) < 0) break;
        if (p[1].revents) break; // stdin closed or written: quit
        if (p[0].revents & (POLLERR | POLLHUP)) break;
        ssize_t n = read(fd, ev, sizeof ev);
        if (n <= 0) break;
        for (ssize_t i = 0; i < n / (ssize_t)sizeof *ev; i++) {
            if (ev[i].type == EV_ABS) printf("A %d %d\n", ev[i].code, ev[i].value);
            else if (ev[i].type == EV_KEY) printf("K %d %d\n", ev[i].code, ev[i].value);
        }
        fflush(stdout);
    }
    ioctl(fd, EVIOCGRAB, 0);
    return 0;
}
