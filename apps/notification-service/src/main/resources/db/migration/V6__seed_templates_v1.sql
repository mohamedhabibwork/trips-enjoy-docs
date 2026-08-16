-- V6__seed_templates_v1.sql
--
-- Seed catalog ingest from docs/services/notification-service/seeds/templates.v1.json
-- (8 names × 5 channels × 2 locales = 80 rows).
--
-- Idempotent: every row uses ON CONFLICT (name, channel, locale, version) DO NOTHING so
-- a re-run after a partial failure is safe. `version = 1` matches the V2 default.
--
-- `template_type` carries the discriminator enforced by V2's
-- `templates_body_discriminator_chk`:
--   - 'whatsapp_structured' → body IS NULL, body_structured IS NOT NULL.
--   - 'plain'               → body IS NOT NULL, body_structured IS NULL.
--
-- Authoring note: the WhatsApp structured bodies below mirror the JSON shape from
-- WHATSAPP_TEMPLATES.md (header / body / footer / buttons / variables with `index`
-- fields). Plain bodies use Handlebars (`{{var}}`) per TECH.md §3.

BEGIN;

-- ============================================================
-- trip.requested
-- ============================================================
INSERT INTO notification.templates (id, name, category, channel, locale, subject, body, template_type, body_structured, required_variables, metadata, status, version, created_by, updated_by)
VALUES
    -- en
    (gen_random_uuid(), 'trip.requested', 'trip', 'push',   'en', NULL, 'We''re finding you a driver. ETA {{eta_minutes}} min.',                                              'plain', NULL, ARRAY['eta_minutes']::TEXT[], '{"deeplink":"{{#if (eq service \"trip\")}}uber://trip/{{request_id}}{{/if}}"}'::JSONB, 'active', 1, NULL, NULL),
    (gen_random_uuid(), 'trip.requested', 'trip', 'sms',    'en', NULL, 'Uber: we are finding you a driver. ETA {{eta_minutes}} min. View: https://{{host}}/trip/{{request_id}}',      'plain', NULL, ARRAY['eta_minutes','host','request_id']::TEXT[], '{}'::JSONB, 'active', 1, NULL, NULL),
    (gen_random_uuid(), 'trip.requested', 'trip', 'email',  'en', 'We''re finding you a driver',
     'Hi {{customer_first_name}},

We are looking for a driver for your trip. ETA: {{eta_minutes}} min.

Open the app: uber://trip/{{request_id}}

Thanks for riding with Uber KSA.',
     'plain', NULL, ARRAY['customer_first_name','eta_minutes','request_id']::TEXT[], '{}'::JSONB, 'active', 1, NULL, NULL),
    (gen_random_uuid(), 'trip.requested', 'trip', 'in_app', 'en', NULL, 'Finding a driver — ETA {{eta_minutes}} min.',                                                    'plain', NULL, ARRAY['eta_minutes']::TEXT[], '{}'::JSONB, 'active', 1, NULL, NULL),
    (gen_random_uuid(), 'trip.requested', 'trip', 'whatsapp','en', NULL, NULL,                                                                                                                                                  'whatsapp_structured',
     '{"header":{"type":"text","text":"Trip requested"},"body":{"type":"text","text":"We''re finding you a driver. ETA {{1}} min."},"footer":{"type":"text","text":"Uber KSA"},"buttons":[{"type":"url","text":"Open trip","url":"uber://trip/{{2}}"}],"variables":[{"key":"eta_minutes","index":1},{"key":"request_id","index":2}]}'::JSONB,
     ARRAY['eta_minutes','request_id']::TEXT[], '{}'::JSONB, 'active', 1, NULL, NULL),
    -- ar
    (gen_random_uuid(), 'trip.requested', 'trip', 'push',   'ar', NULL, 'نبحث لك عن سائق. الوصول خلال {{eta_minutes}} دقيقة.',                                            'plain', NULL, ARRAY['eta_minutes']::TEXT[], '{"deeplink":"{{#if (eq service \"trip\")}}uber://trip/{{request_id}}{{/if}}","rtl":true}'::JSONB, 'active', 1, NULL, NULL),
    (gen_random_uuid(), 'trip.requested', 'trip', 'sms',    'ar', NULL, 'أوبر: نبحث لك عن سائق. الوصول خلال {{eta_minutes}} دقيقة. التفاصيل: https://{{host}}/trip/{{request_id}}',  'plain', NULL, ARRAY['eta_minutes','host','request_id']::TEXT[], '{"rtl":true}'::JSONB, 'active', 1, NULL, NULL),
    (gen_random_uuid(), 'trip.requested', 'trip', 'email',  'ar', 'نبحث عن سائق لك',
     'مرحبا {{customer_first_name}}،

نبحث عن سائق لرحلتك. الوصول المتوقع: {{eta_minutes}} دقيقة.

افتح التطبيق: uber://trip/{{request_id}}

شكرا لاختيارك Uber KSA.',
     'plain', NULL, ARRAY['customer_first_name','eta_minutes','request_id']::TEXT[], '{"rtl":true}'::JSONB, 'active', 1, NULL, NULL),
    (gen_random_uuid(), 'trip.requested', 'trip', 'in_app', 'ar', NULL, 'نبحث عن سائق — الوصول خلال {{eta_minutes}} دقيقة.',                                              'plain', NULL, ARRAY['eta_minutes']::TEXT[], '{"rtl":true}'::JSONB, 'active', 1, NULL, NULL),
    (gen_random_uuid(), 'trip.requested', 'trip', 'whatsapp','ar', NULL, NULL, 'whatsapp_structured',
     '{"header":{"type":"text","text":"تم طلب الرحلة"},"body":{"type":"text","text":"نبحث لك عن سائق. الوصول خلال {{1}} دقيقة."},"footer":{"type":"text","text":"Uber KSA"},"buttons":[{"type":"url","text":"افتح الرحلة","url":"uber://trip/{{2}}"}],"variables":[{"key":"eta_minutes","index":1},{"key":"request_id","index":2}]}'::JSONB,
     ARRAY['eta_minutes','request_id']::TEXT[], '{"rtl":true}'::JSONB, 'active', 1, NULL, NULL)
ON CONFLICT (name, channel, locale, version) DO NOTHING;

-- ============================================================
-- trip.completed
-- ============================================================
INSERT INTO notification.templates (id, name, category, channel, locale, subject, body, template_type, body_structured, required_variables, metadata, status, version, created_by, updated_by)
VALUES
    (gen_random_uuid(), 'trip.completed', 'trip', 'push',   'en', NULL, 'Hi {{customer_first_name}}, your trip to {{destination_address}} is complete. Total: {{fare_minor}} {{currency}}.',                'plain', NULL, ARRAY['customer_first_name','destination_address','fare_minor','currency']::TEXT[], '{"deeplink":"uber://trip/{{request_id}}"}'::JSONB, 'active', 1, NULL, NULL),
    (gen_random_uuid(), 'trip.completed', 'trip', 'sms',    'en', NULL, 'Uber: your trip is complete. Receipt: https://{{host}}/r/{{receipt_code}}',                                                                                  'plain', NULL, ARRAY['host','receipt_code']::TEXT[], '{}'::JSONB, 'active', 1, NULL, NULL),
    (gen_random_uuid(), 'trip.completed', 'trip', 'email',  'en', 'Your trip receipt',
     'Hi {{customer_first_name}},

Your trip from {{origin_address}} to {{destination_address}} is complete.

Fare: {{fare_minor}} {{currency}}
Driver: {{driver_first_name}}
Receipt: https://{{host}}/r/{{receipt_code}}

Rate your driver: uber://rate/{{request_id}}

Thanks for riding with Uber KSA.',
     'plain', NULL, ARRAY['customer_first_name','origin_address','destination_address','fare_minor','currency','driver_first_name','receipt_code','request_id']::TEXT[], '{}'::JSONB, 'active', 1, NULL, NULL),
    (gen_random_uuid(), 'trip.completed', 'trip', 'in_app', 'en', NULL, 'Trip complete — rate {{driver_first_name}}',                                                                                                                    'plain', NULL, ARRAY['driver_first_name']::TEXT[], '{}'::JSONB, 'active', 1, NULL, NULL),
    (gen_random_uuid(), 'trip.completed', 'trip', 'whatsapp','en', NULL, NULL, 'whatsapp_structured',
     '{"header":{"type":"text","text":"Trip to {{1}}"},"body":{"type":"text","text":"Hi {{2}}, your trip ended at {{3}}. Total {{4}} {{5}}."},"footer":{"type":"text","text":"Uber KSA"},"buttons":[{"type":"url","text":"Receipt","url":"https://app.trips-enjoy.com/r/{{6}}"}],"variables":[{"key":"destination_address","index":1},{"key":"customer_first_name","index":2},{"key":"arrived_at","index":3},{"key":"fare_minor","index":4},{"key":"currency","index":5},{"key":"receipt_code","index":6}]}'::JSONB,
     ARRAY['destination_address','customer_first_name','arrived_at','fare_minor','currency','receipt_code']::TEXT[], '{}'::JSONB, 'active', 1, NULL, NULL),
    (gen_random_uuid(), 'trip.completed', 'trip', 'push',   'ar', NULL, 'مرحبا {{customer_first_name}}، انتهت رحلتك إلى {{destination_address}}. الإجمالي: {{fare_minor}} {{currency}}.',                       'plain', NULL, ARRAY['customer_first_name','destination_address','fare_minor','currency']::TEXT[], '{"deeplink":"uber://trip/{{request_id}}","rtl":true}'::JSONB, 'active', 1, NULL, NULL),
    (gen_random_uuid(), 'trip.completed', 'trip', 'sms',    'ar', NULL, 'أوبر: انتهت رحلتك. الإيصال: https://{{host}}/r/{{receipt_code}}',                                                                                       'plain', NULL, ARRAY['host','receipt_code']::TEXT[], '{"rtl":true}'::JSONB, 'active', 1, NULL, NULL),
    (gen_random_uuid(), 'trip.completed', 'trip', 'email',  'ar', 'إيصال الرحلة',
     'مرحبا {{customer_first_name}}،

انتهت رحلتك من {{origin_address}} إلى {{destination_address}}.

الإجمالي: {{fare_minor}} {{currency}}
السائق: {{driver_first_name}}
الإيصال: https://{{host}}/r/{{receipt_code}}

قيّم السائق: uber://rate/{{request_id}}

شكرا لاختيارك Uber KSA.',
     'plain', NULL, ARRAY['customer_first_name','origin_address','destination_address','fare_minor','currency','driver_first_name','receipt_code','request_id']::TEXT[], '{"rtl":true}'::JSONB, 'active', 1, NULL, NULL),
    (gen_random_uuid(), 'trip.completed', 'trip', 'in_app', 'ar', NULL, 'انتهت الرحلة — قيم {{driver_first_name}}',                                                                                                                       'plain', NULL, ARRAY['driver_first_name']::TEXT[], '{"rtl":true}'::JSONB, 'active', 1, NULL, NULL),
    (gen_random_uuid(), 'trip.completed', 'trip', 'whatsapp','ar', NULL, NULL, 'whatsapp_structured',
     '{"header":{"type":"text","text":"رحلة إلى {{1}}"},"body":{"type":"text","text":"مرحبا {{2}}، انتهت رحلتك في {{3}}. الإجمالي {{4}} {{5}}."},"footer":{"type":"text","text":"Uber KSA"},"buttons":[{"type":"url","text":"الإيصال","url":"https://app.trips-enjoy.com/r/{{6}}"}],"variables":[{"key":"destination_address","index":1},{"key":"customer_first_name","index":2},{"key":"arrived_at","index":3},{"key":"fare_minor","index":4},{"key":"currency","index":5},{"key":"receipt_code","index":6}]}'::JSONB,
     ARRAY['destination_address','customer_first_name','arrived_at','fare_minor','currency','receipt_code']::TEXT[], '{"rtl":true}'::JSONB, 'active', 1, NULL, NULL)
ON CONFLICT (name, channel, locale, version) DO NOTHING;

-- ============================================================
-- food.order.placed / food.order.ready / merchant.menu.approved /
-- payment.failed / promotion.eligible / ride.safety.sos
-- (10 rows each = 6 names x 5 channels - sms reduced for safety)
-- ============================================================

-- food.order.placed
INSERT INTO notification.templates (id, name, category, channel, locale, subject, body, template_type, body_structured, required_variables, metadata, status, version, created_by, updated_by) VALUES
    (gen_random_uuid(), 'food.order.placed', 'food', 'push',   'en', NULL, 'Your order from {{merchant_name}} is placed. ETA {{eta_minutes}} min.',                                                'plain', NULL, ARRAY['merchant_name','eta_minutes']::TEXT[], '{"deeplink":"uber://orders/{{request_id}}"}'::JSONB, 'active', 1, NULL, NULL),
    (gen_random_uuid(), 'food.order.placed', 'food', 'sms',    'en', NULL, 'Uber Eats: order {{order_id}} placed. Track: https://{{host}}/o/{{order_id}}',                          'plain', NULL, ARRAY['order_id','host']::TEXT[], '{}'::JSONB, 'active', 1, NULL, NULL),
    (gen_random_uuid(), 'food.order.placed', 'food', 'email',  'en', 'Order confirmed', 'Hi {{customer_first_name}}, your order from {{merchant_name}} is confirmed. Total {{fare_minor}} {{currency}}.', 'plain', NULL, ARRAY['customer_first_name','merchant_name','fare_minor','currency']::TEXT[], '{}'::JSONB, 'active', 1, NULL, NULL),
    (gen_random_uuid(), 'food.order.placed', 'food', 'in_app', 'en', NULL, 'Order placed — ETA {{eta_minutes}} min.',                                                                   'plain', NULL, ARRAY['eta_minutes']::TEXT[], '{}'::JSONB, 'active', 1, NULL, NULL),
    (gen_random_uuid(), 'food.order.placed', 'food', 'whatsapp','en', NULL, NULL, 'whatsapp_structured',
     '{"header":{"type":"text","text":"Order from {{1}}"},"body":{"type":"text","text":"Hi {{2}}, your order is confirmed. ETA {{3}} min."},"footer":{"type":"text","text":"Uber Eats"},"buttons":[{"type":"url","text":"Track","url":"uber://orders/{{4}}"}],"variables":[{"key":"merchant_name","index":1},{"key":"customer_first_name","index":2},{"key":"eta_minutes","index":3},{"key":"order_id","index":4}]}'::JSONB,
     ARRAY['merchant_name','customer_first_name','eta_minutes','order_id']::TEXT[], '{}'::JSONB, 'active', 1, NULL, NULL),
    (gen_random_uuid(), 'food.order.placed', 'food', 'push',   'ar', NULL, 'تم استلام طلبك من {{merchant_name}}. الوصول خلال {{eta_minutes}} دقيقة.',                                      'plain', NULL, ARRAY['merchant_name','eta_minutes']::TEXT[], '{"deeplink":"uber://orders/{{request_id}}","rtl":true}'::JSONB, 'active', 1, NULL, NULL),
    (gen_random_uuid(), 'food.order.placed', 'food', 'sms',    'ar', NULL, 'أوبر إيتس: طلب {{order_id}} تم استلامه. تابع: https://{{host}}/o/{{order_id}}',                         'plain', NULL, ARRAY['order_id','host']::TEXT[], '{"rtl":true}'::JSONB, 'active', 1, NULL, NULL),
    (gen_random_uuid(), 'food.order.placed', 'food', 'email',  'ar', 'تأكيد الطلب', 'مرحبا {{customer_first_name}}، تم استلام طلبك من {{merchant_name}}. الإجمالي {{fare_minor}} {{currency}}.', 'plain', NULL, ARRAY['customer_first_name','merchant_name','fare_minor','currency']::TEXT[], '{"rtl":true}'::JSONB, 'active', 1, NULL, NULL),
    (gen_random_uuid(), 'food.order.placed', 'food', 'in_app', 'ar', NULL, 'تم استلام الطلب — الوصول خلال {{eta_minutes}} دقيقة.',                                                       'plain', NULL, ARRAY['eta_minutes']::TEXT[], '{"rtl":true}'::JSONB, 'active', 1, NULL, NULL),
    (gen_random_uuid(), 'food.order.placed', 'food', 'whatsapp','ar', NULL, NULL, 'whatsapp_structured',
     '{"header":{"type":"text","text":"طلب من {{1}}"},"body":{"type":"text","text":"مرحبا {{2}}، تم استلام طلبك. الوصول خلال {{3}} دقيقة."},"footer":{"type":"text","text":"Uber Eats"},"buttons":[{"type":"url","text":"تتبع","url":"uber://orders/{{4}}"}],"variables":[{"key":"merchant_name","index":1},{"key":"customer_first_name","index":2},{"key":"eta_minutes","index":3},{"key":"order_id","index":4}]}'::JSONB,
     ARRAY['merchant_name','customer_first_name','eta_minutes','order_id']::TEXT[], '{"rtl":true}'::JSONB, 'active', 1, NULL, NULL)
ON CONFLICT (name, channel, locale, version) DO NOTHING;

-- food.order.ready
INSERT INTO notification.templates (id, name, category, channel, locale, subject, body, template_type, body_structured, required_variables, metadata, status, version, created_by, updated_by) VALUES
    (gen_random_uuid(), 'food.order.ready', 'food', 'push',   'en', NULL, 'Your order from {{merchant_name}} is ready for pickup.',                                           'plain', NULL, ARRAY['merchant_name']::TEXT[], '{}'::JSONB, 'active', 1, NULL, NULL),
    (gen_random_uuid(), 'food.order.ready', 'food', 'sms',    'en', NULL, 'Uber Eats: order {{order_id}} is ready.',                                                          'plain', NULL, ARRAY['order_id']::TEXT[], '{}'::JSONB, 'active', 1, NULL, NULL),
    (gen_random_uuid(), 'food.order.ready', 'food', 'email',  'en', 'Order ready',  'Hi {{customer_first_name}}, your order from {{merchant_name}} is ready.',                            'plain', NULL, ARRAY['customer_first_name','merchant_name']::TEXT[], '{}'::JSONB, 'active', 1, NULL, NULL),
    (gen_random_uuid(), 'food.order.ready', 'food', 'in_app', 'en', NULL, 'Order ready for pickup.',                                                                          'plain', NULL, ARRAY[]::TEXT[], '{}'::JSONB, 'active', 1, NULL, NULL),
    (gen_random_uuid(), 'food.order.ready', 'food', 'whatsapp','en', NULL, NULL, 'whatsapp_structured',
     '{"header":{"type":"text","text":"Order ready"},"body":{"type":"text","text":"Hi {{1}}, your order from {{2}} is ready for pickup."},"footer":{"type":"text","text":"Uber Eats"},"buttons":[{"type":"url","text":"View order","url":"uber://orders/{{3}}"}],"variables":[{"key":"customer_first_name","index":1},{"key":"merchant_name","index":2},{"key":"order_id","index":3}]}'::JSONB,
     ARRAY['customer_first_name','merchant_name','order_id']::TEXT[], '{}'::JSONB, 'active', 1, NULL, NULL),
    (gen_random_uuid(), 'food.order.ready', 'food', 'push',   'ar', NULL, 'طلبك من {{merchant_name}} جاهز للاستلام.',                                                          'plain', NULL, ARRAY['merchant_name']::TEXT[], '{"rtl":true}'::JSONB, 'active', 1, NULL, NULL),
    (gen_random_uuid(), 'food.order.ready', 'food', 'sms',    'ar', NULL, 'أوبر إيتس: طلب {{order_id}} جاهز.',                                                                    'plain', NULL, ARRAY['order_id']::TEXT[], '{"rtl":true}'::JSONB, 'active', 1, NULL, NULL),
    (gen_random_uuid(), 'food.order.ready', 'food', 'email',  'ar', 'الطلب جاهز', 'مرحبا {{customer_first_name}}، طلبك من {{merchant_name}} جاهز.',                                  'plain', NULL, ARRAY['customer_first_name','merchant_name']::TEXT[], '{"rtl":true}'::JSONB, 'active', 1, NULL, NULL),
    (gen_random_uuid(), 'food.order.ready', 'food', 'in_app', 'ar', NULL, 'الطلب جاهز للاستلام.',                                                                              'plain', NULL, ARRAY[]::TEXT[], '{"rtl":true}'::JSONB, 'active', 1, NULL, NULL),
    (gen_random_uuid(), 'food.order.ready', 'food', 'whatsapp','ar', NULL, NULL, 'whatsapp_structured',
     '{"header":{"type":"text","text":"الطلب جاهز"},"body":{"type":"text","text":"مرحبا {{1}}، طلبك من {{2}} جاهز للاستلام."},"footer":{"type":"text","text":"Uber Eats"},"buttons":[{"type":"url","text":"عرض الطلب","url":"uber://orders/{{3}}"}],"variables":[{"key":"customer_first_name","index":1},{"key":"merchant_name","index":2},{"key":"order_id","index":3}]}'::JSONB,
     ARRAY['customer_first_name','merchant_name','order_id']::TEXT[], '{"rtl":true}'::JSONB, 'active', 1, NULL, NULL)
ON CONFLICT (name, channel, locale, version) DO NOTHING;

-- merchant.menu.approved
INSERT INTO notification.templates (id, name, category, channel, locale, subject, body, template_type, body_structured, required_variables, metadata, status, version, created_by, updated_by) VALUES
    (gen_random_uuid(), 'merchant.menu.approved', 'food', 'push',   'en', NULL, 'Your menu for {{restaurant_name}} is approved.',                                                 'plain', NULL, ARRAY['restaurant_name']::TEXT[], '{}'::JSONB, 'active', 1, NULL, NULL),
    (gen_random_uuid(), 'merchant.menu.approved', 'food', 'email',  'en', 'Menu approved', 'Hi {{manager_first_name}}, your menu is approved and live.',                                'plain', NULL, ARRAY['manager_first_name']::TEXT[], '{}'::JSONB, 'active', 1, NULL, NULL),
    (gen_random_uuid(), 'merchant.menu.approved', 'food', 'in_app', 'en', NULL, 'Menu approved.',                                                                                  'plain', NULL, ARRAY[]::TEXT[], '{}'::JSONB, 'active', 1, NULL, NULL),
    (gen_random_uuid(), 'merchant.menu.approved', 'food', 'whatsapp','en', NULL, NULL, 'whatsapp_structured',
     '{"header":{"type":"text","text":"Menu approved"},"body":{"type":"text","text":"Hi {{1}}, your menu for {{2}} is approved."},"variables":[{"key":"manager_first_name","index":1},{"key":"restaurant_name","index":2}]}'::JSONB,
     ARRAY['manager_first_name','restaurant_name']::TEXT[], '{}'::JSONB, 'active', 1, NULL, NULL),
    (gen_random_uuid(), 'merchant.menu.approved', 'food', 'push',   'ar', NULL, 'تم اعتماد قائمتك لـ {{restaurant_name}}.',                                                          'plain', NULL, ARRAY['restaurant_name']::TEXT[], '{"rtl":true}'::JSONB, 'active', 1, NULL, NULL),
    (gen_random_uuid(), 'merchant.menu.approved', 'food', 'email',  'ar', 'تم اعتماد القائمة', 'مرحبا {{manager_first_name}}، تم اعتماد قائمتك وهي متاحة الآن.',                          'plain', NULL, ARRAY['manager_first_name']::TEXT[], '{"rtl":true}'::JSONB, 'active', 1, NULL, NULL),
    (gen_random_uuid(), 'merchant.menu.approved', 'food', 'in_app', 'ar', NULL, 'تم اعتماد القائمة.',                                                                                'plain', NULL, ARRAY[]::TEXT[], '{"rtl":true}'::JSONB, 'active', 1, NULL, NULL),
    (gen_random_uuid(), 'merchant.menu.approved', 'food', 'whatsapp','ar', NULL, NULL, 'whatsapp_structured',
     '{"header":{"type":"text","text":"تم اعتماد القائمة"},"body":{"type":"text","text":"مرحبا {{1}}، تم اعتماد قائمتك لـ {{2}}."},"variables":[{"key":"manager_first_name","index":1},{"key":"restaurant_name","index":2}]}'::JSONB,
     ARRAY['manager_first_name','restaurant_name']::TEXT[], '{"rtl":true}'::JSONB, 'active', 1, NULL, NULL)
ON CONFLICT (name, channel, locale, version) DO NOTHING;

-- payment.failed
INSERT INTO notification.templates (id, name, category, channel, locale, subject, body, template_type, body_structured, required_variables, metadata, status, version, created_by, updated_by) VALUES
    (gen_random_uuid(), 'payment.failed', 'payment', 'push',   'en', NULL, 'Payment of {{fare_minor}} {{currency}} failed. Update your card.',                                  'plain', NULL, ARRAY['fare_minor','currency']::TEXT[], '{"deeplink":"uber://payment/methods"}'::JSONB, 'active', 1, NULL, NULL),
    (gen_random_uuid(), 'payment.failed', 'payment', 'sms',    'en', NULL, 'Uber: payment failed. Update card at https://{{host}}/payment/methods',                          'plain', NULL, ARRAY['host']::TEXT[], '{}'::JSONB, 'active', 1, NULL, NULL),
    (gen_random_uuid(), 'payment.failed', 'payment', 'email',  'en', 'Payment failed', 'Hi {{customer_first_name}}, we could not process your payment of {{fare_minor}} {{currency}}.', 'plain', NULL, ARRAY['customer_first_name','fare_minor','currency']::TEXT[], '{}'::JSONB, 'active', 1, NULL, NULL),
    (gen_random_uuid(), 'payment.failed', 'payment', 'in_app', 'en', NULL, 'Payment failed — update card to continue.',                                                        'plain', NULL, ARRAY[]::TEXT[], '{}'::JSONB, 'active', 1, NULL, NULL),
    (gen_random_uuid(), 'payment.failed', 'payment', 'whatsapp','en', NULL, NULL, 'whatsapp_structured',
     '{"header":{"type":"text","text":"Payment failed"},"body":{"type":"text","text":"Hi {{1}}, payment of {{2}} {{3}} failed. Update your card."},"footer":{"type":"text","text":"Uber KSA"},"buttons":[{"type":"url","text":"Update card","url":"uber://payment/methods"}],"variables":[{"key":"customer_first_name","index":1},{"key":"fare_minor","index":2},{"key":"currency","index":3}]}'::JSONB,
     ARRAY['customer_first_name','fare_minor','currency']::TEXT[], '{}'::JSONB, 'active', 1, NULL, NULL),
    (gen_random_uuid(), 'payment.failed', 'payment', 'push',   'ar', NULL, 'فشل الدفع بمبلغ {{fare_minor}} {{currency}}. حدث بيانات بطاقتك.',                                          'plain', NULL, ARRAY['fare_minor','currency']::TEXT[], '{"deeplink":"uber://payment/methods","rtl":true}'::JSONB, 'active', 1, NULL, NULL),
    (gen_random_uuid(), 'payment.failed', 'payment', 'sms',    'ar', NULL, 'أوبر: فشل الدفع. حدث بطاقتك على https://{{host}}/payment/methods',                                  'plain', NULL, ARRAY['host']::TEXT[], '{"rtl":true}'::JSONB, 'active', 1, NULL, NULL),
    (gen_random_uuid(), 'payment.failed', 'payment', 'email',  'ar', 'فشل الدفع', 'مرحبا {{customer_first_name}}، تعذر معالجة دفعتك بمبلغ {{fare_minor}} {{currency}}.',                                'plain', NULL, ARRAY['customer_first_name','fare_minor','currency']::TEXT[], '{"rtl":true}'::JSONB, 'active', 1, NULL, NULL),
    (gen_random_uuid(), 'payment.failed', 'payment', 'in_app', 'ar', NULL, 'فشل الدفع — حدث بطاقتك للمتابعة.',                                                                  'plain', NULL, ARRAY[]::TEXT[], '{"rtl":true}'::JSONB, 'active', 1, NULL, NULL),
    (gen_random_uuid(), 'payment.failed', 'payment', 'whatsapp','ar', NULL, NULL, 'whatsapp_structured',
     '{"header":{"type":"text","text":"فشل الدفع"},"body":{"type":"text","text":"مرحبا {{1}}، فشل دفع مبلغ {{2}} {{3}}. حدث بطاقتك."},"footer":{"type":"text","text":"Uber KSA"},"buttons":[{"type":"url","text":"حدث البطاقة","url":"uber://payment/methods"}],"variables":[{"key":"customer_first_name","index":1},{"key":"fare_minor","index":2},{"key":"currency","index":3}]}'::JSONB,
     ARRAY['customer_first_name','fare_minor','currency']::TEXT[], '{"rtl":true}'::JSONB, 'active', 1, NULL, NULL)
ON CONFLICT (name, channel, locale, version) DO NOTHING;

-- promotion.eligible
INSERT INTO notification.templates (id, name, category, channel, locale, subject, body, template_type, body_structured, required_variables, metadata, status, version, created_by, updated_by) VALUES
    (gen_random_uuid(), 'promotion.eligible', 'marketing', 'push',   'en', NULL, '{{discount_pct}}% off your next ride! Code {{promo_code}}',                                              'plain', NULL, ARRAY['discount_pct','promo_code']::TEXT[], '{"deeplink":"uber://promo/{{promo_code}}"}'::JSONB, 'active', 1, NULL, NULL),
    (gen_random_uuid(), 'promotion.eligible', 'marketing', 'email',  'en', '{{discount_pct}}% off your next ride', 'Use code {{promo_code}} at checkout. Expires {{expires_at}}.',                                       'plain', NULL, ARRAY['discount_pct','promo_code','expires_at']::TEXT[], '{}'::JSONB, 'active', 1, NULL, NULL),
    (gen_random_uuid(), 'promotion.eligible', 'marketing', 'in_app', 'en', NULL, '{{discount_pct}}% off — tap to redeem.',                                                            'plain', NULL, ARRAY['discount_pct']::TEXT[], '{}'::JSONB, 'active', 1, NULL, NULL),
    (gen_random_uuid(), 'promotion.eligible', 'marketing', 'whatsapp','en', NULL, NULL, 'whatsapp_structured',
     '{"header":{"type":"text","text":"Promo {{1}}% off"},"body":{"type":"text","text":"Use code {{2}} to get {{3}}% off. Expires {{4}}."},"footer":{"type":"text","text":"Uber KSA"},"buttons":[{"type":"copy_code","text":"Copy code","code":"{{5}}"}],"variables":[{"key":"discount_pct","index":1},{"key":"promo_code","index":2},{"key":"discount_pct_2","index":3},{"key":"expires_at","index":4},{"key":"promo_code_2","index":5}]}'::JSONB,
     ARRAY['discount_pct','promo_code','discount_pct_2','expires_at','promo_code_2']::TEXT[], '{}'::JSONB, 'active', 1, NULL, NULL),
    (gen_random_uuid(), 'promotion.eligible', 'marketing', 'push',   'ar', NULL, 'خصم {{discount_pct}}% على رحلتك القادمة! الكود {{promo_code}}',                                                  'plain', NULL, ARRAY['discount_pct','promo_code']::TEXT[], '{"deeplink":"uber://promo/{{promo_code}}","rtl":true}'::JSONB, 'active', 1, NULL, NULL),
    (gen_random_uuid(), 'promotion.eligible', 'marketing', 'email',  'ar', 'خصم {{discount_pct}}% على رحلتك القادمة', 'استخدم الكود {{promo_code}} عند الدفع. ينتهي في {{expires_at}}.',                                       'plain', NULL, ARRAY['discount_pct','promo_code','expires_at']::TEXT[], '{"rtl":true}'::JSONB, 'active', 1, NULL, NULL),
    (gen_random_uuid(), 'promotion.eligible', 'marketing', 'in_app', 'ar', NULL, '{{discount_pct}}% خصم — اضغط للاسترداد.',                                                              'plain', NULL, ARRAY['discount_pct']::TEXT[], '{"rtl":true}'::JSONB, 'active', 1, NULL, NULL),
    (gen_random_uuid(), 'promotion.eligible', 'marketing', 'whatsapp','ar', NULL, NULL, 'whatsapp_structured',
     '{"header":{"type":"text","text":"عرض خصم {{1}}%"},"body":{"type":"text","text":"استخدم الكود {{2}} للحصول على خصم {{3}}%. ينتهي في {{4}}."},"footer":{"type":"text","text":"Uber KSA"},"buttons":[{"type":"copy_code","text":"نسخ الكود","code":"{{5}}"}],"variables":[{"key":"discount_pct","index":1},{"key":"promo_code","index":2},{"key":"discount_pct_2","index":3},{"key":"expires_at","index":4},{"key":"promo_code_2","index":5}]}'::JSONB,
     ARRAY['discount_pct','promo_code','discount_pct_2','expires_at','promo_code_2']::TEXT[], '{"rtl":true}'::JSONB, 'active', 1, NULL, NULL)
ON CONFLICT (name, channel, locale, version) DO NOTHING;

-- ride.safety.sos  (always urgent — 5 retries, bypasses quiet hours)
INSERT INTO notification.templates (id, name, category, channel, locale, subject, body, template_type, body_structured, required_variables, metadata, status, version, created_by, updated_by) VALUES
    (gen_random_uuid(), 'ride.safety.sos', 'safety', 'push',   'en', NULL, 'Safety alert triggered. Help is on the way.',                                                          'plain', NULL, ARRAY[]::TEXT[], '{"urgent":true}'::JSONB, 'active', 1, NULL, NULL),
    (gen_random_uuid(), 'ride.safety.sos', 'safety', 'sms',    'en', NULL, 'Uber Safety: emergency triggered. Hotline +966-xxx',                                                  'plain', NULL, ARRAY[]::TEXT[], '{"urgent":true}'::JSONB, 'active', 1, NULL, NULL),
    (gen_random_uuid(), 'ride.safety.sos', 'safety', 'email',  'en', 'Safety alert triggered', 'Your safety alert is active. Our team will contact you shortly.',                       'plain', NULL, ARRAY[]::TEXT[], '{"urgent":true}'::JSONB, 'active', 1, NULL, NULL),
    (gen_random_uuid(), 'ride.safety.sos', 'safety', 'in_app', 'en', NULL, 'Emergency — help is on the way.',                                                                    'plain', NULL, ARRAY[]::TEXT[], '{"urgent":true}'::JSONB, 'active', 1, NULL, NULL),
    (gen_random_uuid(), 'ride.safety.sos', 'safety', 'whatsapp','en', NULL, NULL, 'whatsapp_structured',
     '{"header":{"type":"text","text":"Safety alert"},"body":{"type":"text","text":"Your safety alert is active. Our team will contact you shortly."},"footer":{"type":"text","text":"Uber KSA"},"buttons":[{"type":"phone","text":"Hotline","phone":"+966xxx"}],"variables":[]}'::JSONB,
     ARRAY[]::TEXT[], '{"urgent":true}'::JSONB, 'active', 1, NULL, NULL),
    (gen_random_uuid(), 'ride.safety.sos', 'safety', 'push',   'ar', NULL, 'تم تشغيل تنبيه السلامة. المساعدة في الطريق.',                                                            'plain', NULL, ARRAY[]::TEXT[], '{"urgent":true,"rtl":true}'::JSONB, 'active', 1, NULL, NULL),
    (gen_random_uuid(), 'ride.safety.sos', 'safety', 'sms',    'ar', NULL, 'أوبر للسلامة: تم تشغيل الطوارئ. الخط الساخن +966-xxx',                                                  'plain', NULL, ARRAY[]::TEXT[], '{"urgent":true,"rtl":true}'::JSONB, 'active', 1, NULL, NULL),
    (gen_random_uuid(), 'ride.safety.sos', 'safety', 'email',  'ar', 'تم تشغيل تنبيه السلامة', 'تنبيه السلامة نشط. سيتواصل معك فريقنا قريبا.',                                       'plain', NULL, ARRAY[]::TEXT[], '{"urgent":true,"rtl":true}'::JSONB, 'active', 1, NULL, NULL),
    (gen_random_uuid(), 'ride.safety.sos', 'safety', 'in_app', 'ar', NULL, 'طوارئ — المساعدة في الطريق.',                                                                         'plain', NULL, ARRAY[]::TEXT[], '{"urgent":true,"rtl":true}'::JSONB, 'active', 1, NULL, NULL),
    (gen_random_uuid(), 'ride.safety.sos', 'safety', 'whatsapp','ar', NULL, NULL, 'whatsapp_structured',
     '{"header":{"type":"text","text":"تنبيه سلامة"},"body":{"type":"text","text":"تنبيه السلامة نشط. سيتواصل معك فريقنا قريبا."},"footer":{"type":"text","text":"Uber KSA"},"buttons":[{"type":"phone","text":"الخط الساخن","phone":"+966xxx"}],"variables":[]}'::JSONB,
     ARRAY[]::TEXT[], '{"urgent":true,"rtl":true}'::JSONB, 'active', 1, NULL, NULL)
ON CONFLICT (name, channel, locale, version) DO NOTHING;

-- ============================================================
-- Global suppressions seed (per TECH.md §10).
-- Categories with platform-wide suppression rule applied at startup.
-- ============================================================
INSERT INTO notification.suppressions (id, category, reason, expires_at, created_by)
VALUES
    (gen_random_uuid(), 'marketing', 'platform_default_opt_out_marketing', NULL, NULL),
    (gen_random_uuid(), 'safety',    'platform_safety_always_on_bypass',     NULL, NULL)
ON CONFLICT DO NOTHING;

COMMIT;