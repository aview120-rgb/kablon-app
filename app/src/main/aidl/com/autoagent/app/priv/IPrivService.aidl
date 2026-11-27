package com.autoagent.app.priv;

/** Runs as the shell user through Shizuku (wireless debugging), never as root. */
interface IPrivService {
    /** Reserved by Shizuku: called when the service is unbound with remove=true. */
    void destroy() = 16777114;

    /** Diagnostic text: uid, whether the phone exposes an in-call (uplink) audio output, errors. */
    String probe() = 1;

    /** Plays 16-bit mono PCM from [pcm] into the active call's uplink. Blocks until done. Returns "" or an error. */
    String play(in ParcelFileDescriptor pcm, int sampleRate) = 2;

    /** Starts capturing the call audio (both sides) as 16-bit mono PCM; returns the read end of a pipe. */
    ParcelFileDescriptor startCapture(int sampleRate) = 3;

    void stopCapture() = 4;

    /** Adds [component] to the enabled accessibility services. Returns "" or an error. */
    String enableAccessibility(String component) = 5;

    /** Removes and re-adds [component] so Android binds the accessibility service again. Returns "" or an error. */
    String restartAccessibility(String component) = 6;
}
