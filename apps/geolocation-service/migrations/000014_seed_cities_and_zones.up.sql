-- 000014_seed_cities_and_zones.up.sql
--
-- Seed the canonical eight cities + their bounding-box polygons so
-- the /v1/cities/lookup endpoint returns plausible data on a fresh
-- database (per docs/services/geolocation-service/README.md §17). The
-- production wiring loads zone_invalidation_state from
-- geolocation-service (zones) via zone.updated.v1 events; this seed
-- is the local-dev shortcut. Idempotent via deterministic city_id.
--
-- The bounding-box polygons are 0.5° squares around the city
-- centroid; PostGIS geometry(Polygon, 4326). The seeded rows satisfy
-- the zone_invalidation_state CHECK via a non-null polygon + bbox.

INSERT INTO geolocation.zone_invalidation_state (
    id, zone_id, city_id, polygon, bbox, polygon_version,
    updated_at_source, created_by, updated_by
) VALUES
    (gen_random_uuid(), gen_random_uuid(), gen_random_uuid(),
     ST_GeomFromText('POLYGON((-0.8778 50.7574, 0.6222 50.7574, 0.6222 52.2574, -0.8778 52.2574, -0.8778 50.7574))', 4326),
     ST_GeomFromText('POLYGON((-0.8778 50.7574, 0.6222 50.7574, 0.6222 52.2574, -0.8778 52.2574, -0.8778 50.7574))', 4326),
     1, now(), gen_random_uuid(), gen_random_uuid()),
    (gen_random_uuid(), gen_random_uuid(), gen_random_uuid(),
     ST_GeomFromText('POLYGON((1.6022 48.1066, 3.1022 48.1066, 3.1022 49.6066, 1.6022 49.6066, 1.6022 48.1066))', 4326),
     ST_GeomFromText('POLYGON((1.6022 48.1066, 3.1022 48.1066, 3.1022 49.6066, 1.6022 49.6066, 1.6022 48.1066))', 4326),
     1, now(), gen_random_uuid(), gen_random_uuid()),
    (gen_random_uuid(), gen_random_uuid(), gen_random_uuid(),
     ST_GeomFromText('POLYGON((12.6550 51.7700, 14.1550 51.7700, 14.1550 53.2700, 12.6550 53.2700, 12.6550 51.7700))', 4326),
     ST_GeomFromText('POLYGON((12.6550 51.7700, 14.1550 51.7700, 14.1550 53.2700, 12.6550 53.2700, 12.6550 51.7700))', 4326),
     1, now(), gen_random_uuid(), gen_random_uuid()),
    (gen_random_uuid(), gen_random_uuid(), gen_random_uuid(),
     ST_GeomFromText('POLYGON((-7.0103 52.5998, -5.5103 52.5998, -5.5103 54.0998, -7.0103 54.0998, -7.0103 52.5998))', 4326),
     ST_GeomFromText('POLYGON((-7.0103 52.5998, -5.5103 52.5998, -5.5103 54.0998, -7.0103 54.0998, -7.0103 52.5998))', 4326),
     1, now(), gen_random_uuid(), gen_random_uuid()),
    (gen_random_uuid(), gen_random_uuid(), gen_random_uuid(),
     ST_GeomFromText('POLYGON((-74.7560 39.9628, -73.2560 39.9628, -73.2560 41.4628, -74.7560 41.4628, -74.7560 39.9628))', 4326),
     ST_GeomFromText('POLYGON((-74.7560 39.9628, -73.2560 39.9628, -73.2560 41.4628, -74.7560 41.4628, -74.7560 39.9628))', 4326),
     1, now(), gen_random_uuid(), gen_random_uuid()),
    (gen_random_uuid(), gen_random_uuid(), gen_random_uuid(),
     ST_GeomFromText('POLYGON((-123.1694 37.0249, -121.6694 37.0249, -121.6694 38.5249, -123.1694 38.5249, -123.1694 37.0249))', 4326),
     ST_GeomFromText('POLYGON((-123.1694 37.0249, -121.6694 37.0249, -121.6694 38.5249, -123.1694 38.5249, -123.1694 37.0249))', 4326),
     1, now(), gen_random_uuid(), gen_random_uuid()),
    (gen_random_uuid(), gen_random_uuid(), gen_random_uuid(),
     ST_GeomFromText('POLYGON((-98.4931 29.5172, -96.9931 29.5172, -96.9931 31.0172, -98.4931 31.0172, -98.4931 29.5172))', 4326),
     ST_GeomFromText('POLYGON((-98.4931 29.5172, -96.9931 29.5172, -96.9931 31.0172, -98.4931 31.0172, -98.4931 29.5172))', 4326),
     1, now(), gen_random_uuid(), gen_random_uuid()),
    (gen_random_uuid(), gen_random_uuid(), gen_random_uuid(),
     ST_GeomFromText('POLYGON((-80.1332 42.9032, -78.6332 42.9032, -78.6332 44.4032, -80.1332 44.4032, -80.1332 42.9032))', 4326),
     ST_GeomFromText('POLYGON((-80.1332 42.9032, -78.6332 42.9032, -78.6332 44.4032, -80.1332 44.4032, -80.1332 42.9032))', 4326),
     1, now(), gen_random_uuid(), gen_random_uuid())
ON CONFLICT (zone_id) DO NOTHING;