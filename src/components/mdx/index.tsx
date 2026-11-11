import defaultMdxComponents from 'fumadocs-ui/mdx';
import { Step, Steps } from 'fumadocs-ui/components/steps';
import { Tab, Tabs } from 'fumadocs-ui/components/tabs';
import { Accordion, Accordions } from 'fumadocs-ui/components/accordion';
import { File, Files, Folder } from 'fumadocs-ui/components/files';
import type { MDXComponents } from 'mdx/types';
import { Mermaid } from './mermaid';
import { ReqMeta } from '../req-meta';
import { TypeTable } from '../type-table';
import {
  MindmapBlock,
  RecommendationCard,
  SystemStage,
  TaskCard,
  TraceBar,
} from '../docs-kit';

export function getMDXComponents(components?: MDXComponents) {
  return {
    ...defaultMdxComponents,
    Steps,
    Step,
    Mermaid,
    ReqMeta,
    // prop-driven API table — data lives in the `type` prop, not children
    TypeTable,
    // reusable cross-linked documentation kit
    TraceBar,
    TaskCard,
    RecommendationCard,
    SystemStage,
    MindmapBlock,
    Tabs,
    Tab,
    Accordions,
    Accordion,
    Files,
    File,
    Folder,
    ...components,
  } satisfies MDXComponents;
}

export const useMDXComponents = getMDXComponents;

declare global {
  type MDXProvidedComponents = ReturnType<typeof getMDXComponents>;
}
