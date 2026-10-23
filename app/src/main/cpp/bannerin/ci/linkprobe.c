/*
 * Link probe for libbannerin_guest.a: references every function of the C ABI so the whole
 * library is pulled into a shared object linked the way the libfakeinput.so builds link it.
 * ci/build.sh then checks that object's dynamic dependencies and exports. Valid C and C++.
 */
#include "bannerin.h"

#include <fcntl.h>
#include <string.h>

#ifdef __cplusplus
extern "C" {
#endif

__attribute__((visibility("default"))) long bannerin_linkprobe_entry(int slot);

/* struct bannerin_identity must match the library's layout (core/src/guest.rs IdentityView). */
typedef char bannerin_identity_size_check[(sizeof(struct bannerin_identity) == 92) ? 1 : -1];
typedef char bannerin_identity_key_check[(offsetof(struct bannerin_identity, key) == 12) ? 1 : -1];
typedef char bannerin_identity_name_check[(offsetof(struct bannerin_identity, name) == 28) ? 1 : -1];
/* struct bannerin_ff_effect must match the library's (core/src/ff.rs FfEffect). */
typedef char bannerin_ff_effect_size_check[(sizeof(struct bannerin_ff_effect) == 44) ? 1 : -1];
typedef char bannerin_ff_effect_u_check[(offsetof(struct bannerin_ff_effect, u) == 12) ? 1 : -1];
typedef char bannerin_ff_effect_delay_check[(offsetof(struct bannerin_ff_effect, replay_delay) == 8) ? 1 : -1];

static void probe_log(int level, const char *line)
{
    (void)level;
    (void)line;
}

long bannerin_linkprobe_entry(int slot)
{
    bannerin_handle h = 0;
    struct bannerin_state st;
    struct bannerin_stats gs;
    struct bannerin_slot_stats ss;
    struct bannerin_identity id;
    struct bannerin_ff_effect fx;
    uint8_t ffbits[16];
    unsigned char buf[BANNERIN_EVENT_SIZE * 4];
    long acc = 0;
    int fd;

    bannerin_set_logger(probe_log);
    acc += bannerin_init();
    fd = bannerin_attach(slot, O_RDONLY | O_NONBLOCK | O_CLOEXEC, &h);
    if (fd >= 0) {
        acc += bannerin_fd(h) + bannerin_slot(h) + bannerin_is_removed(h) + bannerin_has_pending(h);
        acc += bannerin_poll_revents(h, 1);
        acc += (long)bannerin_read(h, buf, sizeof buf, 1);
        acc += bannerin_current_state(h, &st);
        acc += bannerin_identity(h, &id, sizeof id);
        acc += bannerin_ff_bits(h, ffbits, sizeof ffbits);
        memset(&fx, 0, sizeof fx);
        fx.type = BANNERIN_FF_RUMBLE;
        fx.id = -1;
        fx.u.rumble.strong_magnitude = 0x8000;
        acc += bannerin_ff_upload(h, &fx, sizeof fx);
        acc += bannerin_ff_play(h, fx.id, 1) + bannerin_ff_set_gain(h, 0xffff) + bannerin_ff_set_autocenter(h, 0);
        acc += bannerin_ff_erase(h, fx.id);
        acc += bannerin_detach(h);
    }
    acc += bannerin_get_stats(&gs, sizeof gs);
    acc += bannerin_slot_stats(slot, &ss, sizeof ss);
    acc += bannerin_slot_identity(slot, &id, sizeof id);
    acc += (long)bannerin_api_version();
    acc += (long)bannerin_axis_hz() + bannerin_axis_auto();
    bannerin_set_logger(0);
    return acc;
}

#ifdef __cplusplus
}
#endif
