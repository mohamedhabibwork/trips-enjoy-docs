-- 000005_zone_invalidation_state.down.sql
DROP INDEX IF EXISTS geolocation.zone_inv_state_bbox_gist;
DROP INDEX IF EXISTS geolocation.zone_inv_state_polygon_gist;
DROP TABLE IF EXISTS geolocation.zone_invalidation_state;