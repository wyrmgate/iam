/** @type {import('@docusaurus/types').Config} */
const config = {
  title: 'Wyrmgate IAM',
  tagline: 'Identity governance and administration documentation',
  url: 'https://docs.wyrmgate.com',
  baseUrl: '/',
  trailingSlash: false,
  onBrokenLinks: 'throw',
  onBrokenMarkdownLinks: 'throw',
  organizationName: 'wyrmgate',
  projectName: 'iam',
  presets: [
    [
      'classic',
      {
        docs: {
          path: '../docs/public',
          routeBasePath: '/',
          sidebarPath: './sidebars.js',
          showLastUpdateAuthor: false,
          showLastUpdateTime: false,
        },
        blog: false,
        pages: false,
        theme: {
          customCss: './src/css/custom.css',
        },
      },
    ],
  ],
  themeConfig: {
    navbar: {
      title: 'Wyrmgate IAM',
      items: [
        {to: '/', label: 'Documentation', position: 'left'},
        {href: 'https://github.com/wyrmgate', label: 'GitHub', position: 'right'},
      ],
    },
    footer: {
      style: 'dark',
      copyright: `Copyright © ${new Date().getFullYear()} Wyrmgate.`,
    },
  },
};

export default config;
