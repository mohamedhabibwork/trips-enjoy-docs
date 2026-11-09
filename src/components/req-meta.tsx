export type ReqPriority = 'Must' | 'Should' | 'Could';

/**
 * Tone for each MoSCoW priority level, as Tailwind classes on the badge.
 * (Chosen defaults — adjust freely; empty string falls back to neutral.)
 */
const priorityTone: Record<ReqPriority, string> = {
  Must: 'bg-red-2/60 text-red-11 border-red-6/50',
  Should: 'bg-amber-2/60 text-amber-11 border-amber-6/50',
  Could: 'bg-gray-2/60 text-gray-11 border-gray-6/50',
};

const neutralTone = 'bg-fc-muted text-fc-muted-foreground';

/** BRD §6.x module section anchors (see content/docs/brd/requirements.mdx). */
const BRD_MODULE_ANCHORS: Record<string, string> = {
  AUTH: '61-accounts-and-access-auth',
  ONB: '62-applications-and-approvals-onb',
  ORG: '63-organizations-and-employees-org',
  TRIP: '64-trips-programs-and-fleet-trip-prg-flt',
  PRG: '64-trips-programs-and-fleet-trip-prg-flt',
  FLT: '64-trips-programs-and-fleet-trip-prg-flt',
  STF: '65-staffing-and-deals-stf',
  NEG: '65-staffing-and-deals-stf',
  SEAT: '66-seats-seat',
  BKG: '67-booking-and-payment-bkg',
  PAY: '67-booking-and-payment-bkg',
  CPN: '68-coupons-cpn',
  CHK: '69-check-in-moderators-and-tracking-chk-mod-trk',
  MOD: '69-check-in-moderators-and-tracking-chk-mod-trk',
  TRK: '69-check-in-moderators-and-tracking-chk-mod-trk',
  PAR: '610-parents-and-children-par',
  MED: '611-media-and-photographers-med-pho',
  PHO: '611-media-and-photographers-med-pho',
  NTF: '612-notifications-ntf',
  SUB: '613-plans-and-subscriptions-sub',
  ACC: '614-accounting-invoices-and-settlements-acc',
  ADM: '615-platform-administration-adm',
  MKT: '616-markets-mkt',
  ANL: '617-analytics-anl',
};

const NFR_ANCHORS: Record<string, string> = {
  PERF: '81-performance-nfr-perf',
  SEC: '82-security-nfr-sec',
  AVL: '83-availability-nfr-avl',
  SCL: '84-scalability-nfr-scl',
  DAT: '85-data-nfr-dat',
  OBS: '86-observability-nfr-obs',
  I18N: '87-internationalization-nfr-i18n',
  A11Y: '88-accessibility-nfr-a11y',
  MNT: '89-maintainability-nfr-mnt',
  COM: '810-compliance-nfr-com',
  BKP: '811-backup-and-recovery-bkp',
};

/** Resolve a source ID to the page section that defines it, if we know it. */
export function sourceHref(id: string): string | undefined {
  let m = id.match(/^BR-([A-Z]+)-\d+$/);
  if (m) {
    const anchor = BRD_MODULE_ANCHORS[m[1]];
    if (anchor) return `/en/docs/brd/requirements#${anchor}`;
    return '/en/docs/brd/requirements';
  }
  if (/^BRL-\d+$/.test(id)) return '/en/docs/brd/requirements#7-business-rules';
  if (/^OD-\d+$/.test(id)) return '/en/docs/brd/compliance#11-open-decisions';
  m = id.match(/^NFR-([A-Z]+)-\d+$/);
  if (m) {
    const anchor = NFR_ANCHORS[m[1]];
    if (anchor) return `/en/docs/srs/quality#${anchor}`;
  }
  if (/^DAT-(7|8|9)$/.test(id)) return '/en/docs/srs/data#63-soft-delete-immutability-and-deletion';
  if (/^DAT-\d+$/.test(id)) return '/en/docs/srs/data#62-data-isolation';
  // D-nn: external decision register — intentionally unlinked
  return undefined;
}

/**
 * Traceability line for a functional requirement: MoSCoW priority badge
 * plus the upstream requirement IDs it traces to
 * (e.g. BR-AUTH-01; BRL-20; D-15; OD-06), each linking to its definition.
 */
export function ReqMeta({ priority, source }: { priority: ReqPriority; source: string }) {
  const ids = source
    .split(/[;,]/)
    .map((id) => id.trim())
    .filter(Boolean);

  return (
    <p className="not-prose my-1 flex flex-wrap items-center gap-x-2 gap-y-1 text-sm text-fc-muted-foreground">
      <span
        className={`rounded-full border px-2 py-0.5 text-xs font-medium ${priorityTone[priority] || neutralTone}`}
      >
        {priority}
      </span>
      <span className="text-xs uppercase tracking-wide">source</span>
      {ids.map((id) => {
        const href = sourceHref(id);
        return href ? (
          <a key={id} href={href} className="rounded bg-fc-muted px-1 py-0.5 text-xs no-underline">
            {id}
          </a>
        ) : (
          <code key={id} className="rounded bg-fc-muted px-1 py-0.5 text-xs">
            {id}
          </code>
        );
      })}
    </p>
  );
}
