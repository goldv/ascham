package io.ito.cold;

import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import org.apache.hadoop.conf.Configuration;
import org.apache.iceberg.CatalogProperties;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.hadoop.HadoopCatalog;
import org.apache.iceberg.rest.RESTCatalog;

/**
 * One destination string, one catalog. A local path or {@code file://} URI opens a serverless
 * {@link HadoopCatalog} rolling straight to disk; an {@code http(s)://} URI opens a
 * {@link RESTCatalog} (the dev stack's Lakekeeper, or any Iceberg REST service), which owns the
 * object storage underneath.
 *
 * <p>A bare {@code s3://} warehouse is rejected by design: the Hadoop catalog commits by renaming
 * its metadata file, which is not atomic on object stores — a crashed commit can corrupt the
 * table pointer. S3-resident data always goes through a REST catalog.
 */
final class CatalogFactory {

    private CatalogFactory() {
    }

    static Catalog open(ColdConfig config) {
        String destination = config.destination();
        if (destination.startsWith("http://") || destination.startsWith("https://")) {
            return rest(config);
        }
        if (destination.startsWith("s3://")) {
            throw new ColdException("object-store warehouses need a REST catalog: point destination "
                    + "at the catalog endpoint (http(s)://...) instead of " + destination);
        }
        return hadoop(config);
    }

    private static Catalog rest(ColdConfig config) {
        Map<String, String> props = new HashMap<>();
        props.put(CatalogProperties.URI, config.destination());
        props.put(CatalogProperties.WAREHOUSE_LOCATION, config.warehouseName());
        ColdConfig.S3Credentials s3 = config.s3();
        if (s3 != null) {
            // Explicit client-side credentials. Usually unnecessary: the dev catalog vends
            // prefix-scoped credentials with the table config (dev/README.md).
            props.put("io-impl", "org.apache.iceberg.aws.s3.S3FileIO");
            props.put("s3.endpoint", s3.endpoint());
            props.put("s3.access-key-id", s3.keyId());
            props.put("s3.secret-access-key", s3.secret());
            props.put("s3.path-style-access", Boolean.toString(s3.pathStyle()));
        }
        props.putAll(config.catalogProperties());
        RESTCatalog catalog = new RESTCatalog();
        catalog.setConf(new Configuration());
        catalog.initialize("hist", props);
        return catalog;
    }

    private static Catalog hadoop(ColdConfig config) {
        String destination = config.destination();
        String warehouse = destination.startsWith("file://")
                ? destination
                : Paths.get(destination).toAbsolutePath().normalize().toString();
        HadoopCatalog catalog = new HadoopCatalog();
        catalog.setConf(new Configuration());
        Map<String, String> props = new HashMap<>();
        props.put(CatalogProperties.WAREHOUSE_LOCATION, warehouse);
        props.putAll(config.catalogProperties());
        catalog.initialize("hist", props);
        return catalog;
    }
}
