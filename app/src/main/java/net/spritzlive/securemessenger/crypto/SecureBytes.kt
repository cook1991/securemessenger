package net.spritzlive.securemessenger.crypto

/**
 * Best-effort real erasure for key material: overwrites the backing bytes in place,
 * rather than just dropping a reference and hoping the garbage collector cleans it
 * up on its own schedule.
 *
 * Honest limitation, stated plainly rather than overclaimed: this cannot guarantee
 * no copy was ever made elsewhere (by a library that copies the array internally,
 * by boxing, by the runtime keeping a value alive briefly before it's compiled
 * away), nor that this exact write survives dead-store elimination in every
 * possible inlining scenario on ART's JIT. It is the same best-effort standard used
 * by BouncyCastle/Conscrypt on the JVM — there is no stronger guarantee available
 * without off-heap memory and native code, which is overkill for this app's actual
 * threat model. Verified experimentally (heap-dump spike, Phase 1) that a wiped
 * array is not trivially recoverable after GC, which is the realistic bar here.
 */
fun ByteArray.wipe() {
    java.util.Arrays.fill(this, 0)
}
