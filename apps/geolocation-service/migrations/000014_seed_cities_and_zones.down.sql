-- 000014_seed_cities_and_zones.down.sql
DELETE FROM geolocation.zone_invalidation_state
    WHERE polygon_version = 1
      AND updated_at_source = now()::date
      AND city_id IS NOT NULL
      AND ST_Equals(
            polygon,
            ST_GeomFromText('POLYGON((-0.8778 50.7574, 0.6222 50.7574, 0.6222 52.2574, -0.8778 52.2574, -0.8778 50.7574))', 4326)
          );