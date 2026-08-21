import { useForm } from "react-hook-form";
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

export default function CreateOrganisationPage() {
    const {
        register,
        handleSubmit,
        formState: { errors },
    } = useForm<CreateOrganisationFormData>({
        resolver: zodResolver(createOrganisationSchema),
    });

    const queryClient = useQueryClient();
    const navigate = useNavigate();

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
            await queryClient.invalidateQueries({
                queryKey: ["organisations"],
            });

            setCurrentOrganisationId(
                createdOrganisation.id
            );

            navigate("/services");
        },
    });

    function onSubmit(
        data: CreateOrganisationFormData
    ) {
        createMutation.mutate(data);
    }

    return (
        <div className="create-organisation-page">
            <h1>Create Organisation</h1>

            <form
                className="standard-form"
                onSubmit={handleSubmit(onSubmit)}
                noValidate
            >
                <div className="form-field">
                    <label htmlFor="name">
                        Name
                    </label>

                    <input
                        id="name"
                        type="text"
                        {...register("name")}
                    />

                    {errors.name && (
                        <p className="error">
                            {errors.name.message}
                        </p>
                    )}
                </div>

                <div className="form-field">
                    <label htmlFor="slug">
                        Slug
                    </label>

                    <input
                        id="slug"
                        type="text"
                        {...register("slug")}
                    />

                    {errors.slug && (
                        <p className="error">
                            {errors.slug.message}
                        </p>
                    )}
                </div>

                <button
                    type="submit"
                    disabled={createMutation.isPending}
                >
                    {createMutation.isPending
                        ? "Creating organisation..."
                        : "Create Organisation"}
                </button>

                {createMutation.isError && (
                    <p className="error">
                        {createMutation.error.message}
                    </p>
                )}
            </form>
        </div>
    );
}