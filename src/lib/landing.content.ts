import type { LucideIcon } from 'lucide-react';
import {
  Briefcase,
  Building2,
  Camera,
  Car,
  ClipboardList,
  Database,
  Eye,
  HeartHandshake,
  Map,
  Route,
  Shield,
  Users,
} from 'lucide-react';

/**
 * All copy and featured numbers for the landing page live here,
 * so the page layout never needs to change when the docs evolve.
 */

export const hero = {
  badge: 'Product documentation · v1.1 · 9 November 2026',
  // TODO(habib): this tagline is the page's voice — tune it (see request below).
  tagline: 'From business case to booking flow, one traceable set.',
  subtitle:
    'Trips Enjoy is a multi-role trips marketplace — travelers book seats on organized trips, organizations publish and operate them, and drivers, photographers and moderators deliver the experience. This is the complete documentation set: business requirements, software requirements, the data model and the role flows, all cross-referenced by stable IDs.',
};

// TODO(habib): which numbers lead? Draft below; adjust or replace.
export const stats: { value: string; label: string }[] = [
  { value: '201', label: 'Functional requirements' },
  { value: '28', label: 'Modules' },
  { value: '14', label: 'Data domains' },
  { value: '95', label: 'Role flows' },
  { value: '8', label: 'Apps & surfaces' },
];

export type LandingSection = {
  /** docs folder slug, becomes the `/$lang/docs/<slug>` link target */
  slug: string;
  title: string;
  description: string;
  /** small line under the description, e.g. the ID vocabulary of the doc */
  meta: string;
  icon: LucideIcon;
};

export const sections: LandingSection[] = [
  {
    slug: 'brd',
    title: 'Business (BRD)',
    description:
      'Objectives, scope, stakeholders, commercial model, business rules, risks and open decisions.',
    meta: 'BR · BRL · RSK · OD IDs',
    icon: Briefcase,
  },
  {
    slug: 'srs',
    title: 'Software (SRS)',
    description:
      'Functional requirements across every module, data requirements, state machines and quality goals.',
    meta: 'FR IDs · MoSCoW priorities · BRD traceability',
    icon: ClipboardList,
  },
  {
    slug: 'erd',
    title: 'Data Model (ERD)',
    description:
      'Table conventions, the domain map, lookup catalog and row-level security paths.',
    meta: 'Immutable ledgers · TimescaleDB · JSONB i18n',
    icon: Database,
  },
  {
    slug: 'user-flows',
    title: 'User Flows',
    description:
      'How every role registers, gets approved and operates day to day — with rule traces and edge cases.',
    meta: 'UF IDs · BRL/FR traces · screen references',
    icon: Route,
  },
];

export type LandingRole = {
  /** page under the user-flows folder */
  slug: string;
  label: string;
  icon: LucideIcon;
};

export const roles: LandingRole[] = [
  { slug: 'traveler', label: 'Traveler', icon: Map },
  { slug: 'organization', label: 'Organization', icon: Building2 },
  { slug: 'admin', label: 'Admin', icon: Shield },
  { slug: 'driver', label: 'Driver', icon: Car },
  { slug: 'photographer', label: 'Photographer', icon: Camera },
  { slug: 'moderator', label: 'Moderator', icon: Eye },
  { slug: 'parent', label: 'Parent tracker', icon: HeartHandshake },
  { slug: 'shared', label: 'Shared flows', icon: Users },
];

export const idPrefixes: { prefix: string; meaning: string }[] = [
  { prefix: 'BR-*', meaning: 'Business requirements' },
  { prefix: 'BRL-*', meaning: 'Business rules' },
  { prefix: 'FR-*', meaning: 'Functional requirements (MoSCoW-graded)' },
  { prefix: 'D-*', meaning: 'Decided values from the decision register' },
  { prefix: 'OD-*', meaning: 'Open decisions awaiting an owner' },
  { prefix: 'UF-*', meaning: 'User flows' },
];
