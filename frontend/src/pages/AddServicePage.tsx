import {
    useMutation,
    useQueryClient,
} from "@tanstack/react-query";

import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { useNavigate } from "react-router";

import { createMonitoredService } from "../api/monitoredServiceApi";
import { useOrganisation } from "../hooks/useOrganisation";

import {
    createMonitoredServiceSchema,
    environmentValues,
    serviceTypes,
} from "../schemas/createMonitoredServiceSchema";

import type {
    CreateMonitoredServiceFormData,
} from "../schemas/createMonitoredServiceSchema";

export default function AddServicePage() {
    const {
        register,
        handleSubmit,
        formState: { errors },
    } = useForm<CreateMonitoredServiceFormData>({
        resolver: zodResolver(createMonitoredServiceSchema),
    });

    const queryClient = useQueryClient();
    const navigate = useNavigate();

    const { currentOrganisation } = useOrganisation();
    const organisationId = currentOrganisation?.id;

    const createMutation = useMutation({
        mutationFn: (request: CreateMonitoredServiceFormData) => {
            if (!organisationId) {
                throw new Error("Organisation ID is missing.");
            }

            return createMonitoredService(
                organisationId,
                request
            );
        },

        onSuccess: async (createdService) => {
            await queryClient.invalidateQueries({
                queryKey: [
                    "monitored-services",
                    organisationId,
                ],
            });

            navigate(`/services/${createdService.id}`);
        },
    });

    function onSubmit(
        data: CreateMonitoredServiceFormData
    ) {
        createMutation.mutate(data);
    }

    return (
        <div className="add-service-page">
            <h1>Add Service</h1>

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
                        <p className="validation-error">{errors.name.message}</p>
                    )}
                </div>

                <div className="form-field">
                    <label htmlFor="serviceType">
                        Service type
                    </label>

                    <select
                        id="serviceType"
                        {...register("serviceType")}
                    >
                        {serviceTypes.map((item) => (
                            <option
                                key={item}
                                value={item}
                            >
                                {item}
                            </option>
                        ))}
                    </select>

                    {errors.serviceType && (
                        <p>
                            {errors.serviceType.message}
                        </p>
                    )}
                </div>

                <div className="form-field">
                    <label htmlFor="environment">
                        Environment
                    </label>

                    <select
                        id="environment"
                        {...register("environment")}
                    >
                        {environmentValues.map((item) => (
                            <option
                                key={item}
                                value={item}
                            >
                                {item}
                            </option>
                        ))}
                    </select>

                    {errors.environment && (
                        <p className="validation-error">
                            {errors.environment.message}
                        </p>
                    )}
                </div>

                <div className="form-field">
                    <label htmlFor="baseUrl">
                        Base URL
                    </label>

                    <input
                        id="baseUrl"
                        type="text"
                        {...register("baseUrl")}
                    />

                    {errors.baseUrl && (
                        <p className="validation-error">
                            {errors.baseUrl.message}
                        </p>
                    )}
                </div>

                <div className="form-field">
                    <label htmlFor="healthEndpoint">
                        Health Endpoint
                    </label>

                    <input
                        id="healthEndpoint"
                        type="text"
                        {...register("healthEndpoint")}
                    />

                    {errors.healthEndpoint && (
                        <p className="validation-error">
                            {errors.healthEndpoint.message}
                        </p>
                    )}
                </div>

                <div className="form-field">
                    <label htmlFor="owner">
                        Owner
                    </label>

                    <input
                        id="owner"
                        type="text"
                        {...register("owner")}
                    />

                    {errors.owner && (
                        <p className="validation-error">{errors.owner.message}</p>
                    )}
                </div>

                <button
                    type="submit"
                    disabled={
                        createMutation.isPending ||
                        !organisationId
                    }
                >
                    {createMutation.isPending
                        ? "Adding service..."
                        : "Add service"}
                </button>

                {createMutation.isError && (
                    <p>
                        {createMutation.error.message}
                    </p>
                )}
            </form>
        </div>
    );
}