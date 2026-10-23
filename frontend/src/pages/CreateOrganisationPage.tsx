import { useForm, useWatch } from "react-hook-form";
import { useRef, useState } from "react";
import { zodResolver } from "@hookform/resolvers/zod";
import {
    useMutation,
    useQueryClient,
} from "@tanstack/react-query";
import { useNavigate } from "react-router";

import {
    createOrganisationSchema,
} from "../schemas/createOrganisationSchema";

import type {
    CreateOrganisationFormData,
} from "../schemas/createOrganisationSchema";

import { createOrganisation } from "../api/organisationApi";
import { useOrganisation } from "../hooks/useOrganisation";
import { ApiError } from "../api/client";
import type { Organisation } from "../types/Organisation";

export default function CreateOrganisationPage() {
    const {
        register,
        handleSubmit,
        setError,
        control,
        formState: { errors, isSubmitting, touchedFields, isSubmitted },
    } = useForm<CreateOrganisationFormData>({
        resolver: zodResolver(createOrganisationSchema),
        mode: "onTouched",
        reValidateMode: "onChange",
        defaultValues: { name: "", slug: "" },
    });

    const [slugFocused, setSlugFocused] = useState(false);
    const [slugRejection, setSlugRejection] = useState<{ value: string; message: string } | null>(null);
    const slugValue = useWatch({ control, name: "slug" });
    const slugError = slugRejection?.value === slugValue.trim() ? slugRejection.message
        : errors.slug?.type === "server" ? undefined : errors.slug?.message;
    const showSlugHint = slugFocused && !slugError;
    const showSlugValid = !slugError && (slugFocused || touchedFields.slug || isSubmitted)
        && createOrganisationSchema.shape.slug.safeParse(slugValue).success;
    const nameField = register("name");
    const slugField = register("slug");

    const queryClient = useQueryClient();
    const navigate = useNavigate();
    const submissionInProgress = useRef(false);

    const {
        setCurrentOrganisationId,
    } = useOrganisation();

    const createMutation = useMutation({
        mutationFn: (
            request: CreateOrganisationFormData
        ) => {
            return createOrganisation(request);
        },

        onSuccess: async (createdOrganisation) => {
            // Ignore/abort older list responses before seeding the authoritative result.
            await queryClient.cancelQueries({
                queryKey: ["organisations"], exact: true,
            });
            queryClient.setQueryData<Organisation[]>(["organisations"], (current = []) =>
                [...current.filter((organisation) => organisation.id !== createdOrganisation.id), createdOrganisation]
            );

            setCurrentOrganisationId(
                createdOrganisation.id
            );

            navigate("/services", { state: { createdOrganisationId: createdOrganisation.id } });
        },
        onError: (error, request) => {
            if (error instanceof ApiError) {
                for (const field of ["name", "slug"] as const) {
                    if (error.errors[field]) setError(field, { type: "server", message: error.errors[field] });
                }
                if (error.errors.slug) setSlugRejection({ value: request.slug, message: error.errors.slug });
            }
        },
    });

    async function onSubmit(
        data: CreateOrganisationFormData
    ) {
        if (submissionInProgress.current) return;
        submissionInProgress.current = true;
        try {
            await createMutation.mutateAsync(data);
        } catch { /* The mutation and field errors are displayed below. */ }
        finally { submissionInProgress.current = false; }
    }

    return (
        <div className="create-organisation-page">
            <h1>Create Organisation</h1>

            <form
                className="standard-form"
                onSubmit={(event) => { void handleSubmit(onSubmit)(event); }}
                noValidate
            >
                <div className="form-field">
                    <label htmlFor="name">
                        Name
                    </label>

                    <input
                        id="name"
                        type="text"
                        aria-invalid={Boolean(errors.name)}
                        aria-describedby={errors.name ? "name-error" : undefined}
                        {...nameField}
                        onChange={(event) => {
                            void nameField.onChange(event);
                            if (createMutation.isError) createMutation.reset();
                        }}
                    />

                    {errors.name && (
                        <p className="error" id="name-error">
                            {errors.name.message}
                        </p>
                    )}
                </div>

                <div className="form-field">
                    <label htmlFor="slug">
                        Slug
                    </label>

                    <div className="slug-input">
                        <input
                            id="slug"
                            type="text"
                            aria-invalid={Boolean(slugError)}
                            aria-describedby={slugError ? "slug-error" : showSlugHint ? "slug-hint" : undefined}
                            {...slugField}
                            onFocus={() => setSlugFocused(true)}
                            onBlur={(event) => {
                                setSlugFocused(false);
                                void slugField.onBlur(event);
                            }}
                            onChange={(event) => {
                                void slugField.onChange(event);
                                if (createMutation.isError) createMutation.reset();
                            }}
                        />
                        {showSlugValid && (
                            <span className="slug-valid" role="img" aria-label="Slug format is valid" title="Valid slug format">✓</span>
                        )}
                    </div>
                    <div className="slug-feedback" aria-live="polite" aria-atomic="true">
                        {slugError ? (
                            <p className="error" id="slug-error">{slugError}</p>
                        ) : showSlugHint ? (
                            <small id="slug-hint">2–100 lowercase letters, numbers or hyphens.</small>
                        ) : null}
                    </div>
                </div>

                <button
                    type="submit"
                    disabled={createMutation.isPending || isSubmitting}
                >
                    {createMutation.isPending
                        ? "Creating organisation..."
                        : "Create Organisation"}
                </button>

                {createMutation.isError && (
                    <p className="error" role="alert">
                        {createMutation.error.message}
                    </p>
                )}
            </form>
        </div>
    );
}
