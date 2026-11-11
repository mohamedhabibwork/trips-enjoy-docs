#!/usr/bin/env bun
/**
 * Generates the enriched Arabic phased Delivery Plan (`ar/plan/p1..p6.mdx`
 * and `ar/plan/milestones.mdx`) with 1:1 structural and heading parity to English.
 *
 * Run: bun scripts/generate-plan-ar.ts
 */
import { generatePlanForLocale } from './generate-plan';

generatePlanForLocale('ar');
