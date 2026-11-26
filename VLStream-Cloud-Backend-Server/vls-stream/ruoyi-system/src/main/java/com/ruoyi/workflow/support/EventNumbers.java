/*
 * SPDX-FileCopyrightText: 2021 RuoYi-Flowable-Plus
 * SPDX-FileCopyrightText: 2026 OortCloud (https://vls.oortcloudsmart.com/en/)
 * SPDX-License-Identifier: MIT
 * Created by: RuoYi-Flowable-Plus
 * Updated by: ChaoQun Lei
 */

package com.ruoyi.workflow.support;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Single source of truth for the externally visible event number.
 *
 * <p>The legacy event store exposes a composite primary key as {@code id-no}, except for
 * sequence zero where only the bare {@code id} is returned. Cloud-originated events are
 * always stored with sequence zero, so both forms must resolve to the same row. Parsing
 * and formatting live here to keep the callback lookup and the compatibility API aligned.
 */
public final class EventNumbers {

    private EventNumbers() {
    }

    /**
     * Candidate composite keys for one externally supplied event number.
     *
     * <p>The legacy form owns a numeric suffix; the cloud form is the entire value with
     * sequence zero. When the value is a bare identifier both candidates collapse into one.
     */
    public static Set<Key> candidates(String externalId) {
        Set<Key> result = new LinkedHashSet<Key>();
        String value = externalId == null ? "" : externalId;
        Key legacy = parse(value);
        result.add(legacy);
        if (!(legacy.no == 0L && legacy.id.equals(value))) {
            result.add(new Key(value, 0L));
        }
        return result;
    }

    /**
     * Parse the exact three-segment composite ID accepted by the legacy API, else treat the
     * whole value as the ID with sequence zero.
     */
    public static Key parse(String externalId) {
        String[] parts = externalId == null ? new String[0] : externalId.split("-");
        if (parts.length == 3) {
            try {
                return new Key(parts[0] + "-" + parts[1], Long.parseLong(parts[2]));
            } catch (NumberFormatException ignored) {
                // Fall through to the original ID with sequence zero.
            }
        }
        return new Key(externalId == null ? "" : externalId, 0L);
    }

    /**
     * Render the externally visible form; sequence zero omits the suffix.
     */
    public static String externalId(String id, long no) {
        return no == 0L ? id : id + "-" + no;
    }

    /** One composite event primary key. */
    public static final class Key {
        private final String id;
        private final long no;

        public Key(String id, long no) {
            this.id = id;
            this.no = no;
        }

        public String getId() {
            return id;
        }

        public long getNo() {
            return no;
        }

        public String externalId() {
            return EventNumbers.externalId(id, no);
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Key)) {
                return false;
            }
            Key key = (Key) other;
            return no == key.no && id.equals(key.id);
        }

        @Override
        public int hashCode() {
            return 31 * id.hashCode() + (int) (no ^ (no >>> 32));
        }
    }
}
