import { z } from "zod";

export const createOrganisationSchema = z.object({
    name: z.string()
        .trim()
        .min(1, "Name must not be empty.")
        .max(100, "Name must not exceed 100 characters."),

    slug: z.string()
        .trim()
        .min(2, "Slug must not be less than 2 characters.")
        .max(100, "Slug must not exceed 100 characters.")
        .regex(
            /^[a-z0-9]+(?:-[a-z0-9]+)*$/,
            "Slug may contain lowercase letters, numbers, and hyphens between words."
        ),
});

export type CreateOrganisationFormData =
    z.infer<typeof createOrganisationSchema>;