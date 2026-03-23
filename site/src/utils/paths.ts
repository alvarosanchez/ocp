export const withBase = (path: string) => {
  const base = import.meta.env.BASE_URL.replace(/\/$/, '');

  if (path === '/') {
    return base || '/';
  }

  return `${base}${path.startsWith('/') ? path : `/${path}`}`;
};
