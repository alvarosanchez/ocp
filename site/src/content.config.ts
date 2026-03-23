import { defineCollection, z } from 'astro:content';

const docs = defineCollection({
  type: 'content',
  schema: z.object({
    title: z.string(),
    description: z.string(),
    sidebar: z
      .object({
        label: z.string(),
        order: z.number()
      })
      .optional()
  })
});

export const collections = { docs };
