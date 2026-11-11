import { defineI18n } from 'fumadocs-core/i18n';

export const i18n = defineI18n({
  defaultLanguage: 'en',
  languages: ['ar', 'en'],
  // ar/ locale folders (not brd.ar.mdx dot notation)
  parser: 'dir',
});