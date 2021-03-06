package org.limewire.core.api.friend.feature;

import java.net.URI;

import org.limewire.util.Objects;

/**
 * Represents a custom capability that a FriendPresence supports.  A Feature is
 * used to layer custom communication on top of existing social networks, (i.e., jabber)
 * They may represent custom data that enables two friends to make a p2p connection,
 * or an action, such as recommending a file to them. 
 */
public class Feature<T> {
    private final T feature;
    private final URI id;

    public Feature(T feature, URI id) {
        this.feature = Objects.nonNull(feature, "feature");
        this.id = id;
    }

    public T getFeature() {
        return feature;
    }

    public URI getID() {
        return id;
    }
}
