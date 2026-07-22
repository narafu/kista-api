package com.kista.adapter.out.toss;

import java.util.Optional;

interface TossTokenIssuanceLock {

    Optional<Handle> tryAcquire(long lockKey);

    interface Handle extends AutoCloseable {

        @Override
        void close();
    }
}
