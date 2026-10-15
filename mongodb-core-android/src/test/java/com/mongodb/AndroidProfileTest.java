package com.mongodb;

import org.junit.Test;
import com.mongodb.internal.connection.StreamFactoryHelper;
import com.mongodb.connection.TransportSettings;
import com.mongodb.connection.SocketSettings;
import com.mongodb.connection.SslSettings;
import org.bson.Document;
import org.bson.types.Decimal128;
import static org.junit.Assert.*;

public class AndroidProfileTest {
    @Test public void standardSocketFactoryWorksAndCustomTransportRejects() throws Exception {
        try (com.mongodb.internal.connection.StreamFactoryFactory factory =
                StreamFactoryHelper.getSyncStreamFactoryFactory(null, null)) {
            assertNotNull(factory.create(SocketSettings.builder().build(), SslSettings.builder().build()));
        }
        assertThrows(MongoClientException.class,
                () -> StreamFactoryHelper.getSyncStreamFactoryFactory(new TransportSettings() {}, null));
    }
    @Test public void documentAndNumericCodecsArePreserved() {
        assertNotNull(MongoClientSettings.getDefaultCodecRegistry().get(Document.class));
        assertNotNull(MongoClientSettings.getDefaultCodecRegistry().get(Decimal128.class));
        assertNotNull(MongoClientSettings.getDefaultCodecRegistry().get(Long.class));
    }
}
