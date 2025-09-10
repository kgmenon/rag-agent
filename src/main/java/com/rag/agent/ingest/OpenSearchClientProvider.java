package com.rag.agent.ingest;

import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.json.jackson.JacksonJsonpMapper;
import org.opensearch.client.transport.httpclient5.ApacheHttpClient5TransportBuilder;
import software.amazon.awssdk.regions.Region;
import org.apache.hc.core5.http.HttpHost;

import java.net.URI;

public class OpenSearchClientProvider {
    private static OpenSearchClient client;
    
    public static synchronized OpenSearchClient getClient(String endpoint, Region region) {
        if (client == null) {
            client = createClient(endpoint, region);
        }
        return client;
    }
    
    private static OpenSearchClient createClient(String endpoint, Region region) {
        try {
            URI endpointUri = URI.create(endpoint);
            HttpHost host = new HttpHost(endpointUri.getScheme(), endpointUri.getHost(), endpointUri.getPort());
            
            ApacheHttpClient5TransportBuilder builder = ApacheHttpClient5TransportBuilder.builder(host)
                    .setMapper(new JacksonJsonpMapper());
            
            return new OpenSearchClient(builder.build());
            
        } catch (Exception e) {
            throw new RuntimeException("Failed to create OpenSearch client", e);
        }
    }
}