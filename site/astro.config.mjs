import { defineConfig } from 'astro/config';
import mdx from '@astrojs/mdx';
import icon from 'astro-icon';

const siteUrl = 'https://alvarosanchez.github.io';
const projectName = 'ocp';
const isGithubPages = process.env.GITHUB_ACTIONS === 'true' || process.env.DEPLOY_TARGET === 'github-pages';

export default defineConfig({
  site: `${siteUrl}/${projectName}`,
  base: isGithubPages ? `/${projectName}` : '/',
  trailingSlash: 'ignore',
  integrations: [mdx(), icon()]
});
