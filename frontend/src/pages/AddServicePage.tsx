import { useEffect, useRef, useState } from "react";
import type { FocusEvent } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { Link, useNavigate } from "react-router";
import { createMonitoredService } from "../api/monitoredServiceApi";
import { useOrganisation } from "../hooks/useOrganisation";
import { createMonitoredServiceSchema, environmentValues, serviceTypes } from "../schemas/createMonitoredServiceSchema";
import type { CreateMonitoredServiceFormData } from "../schemas/createMonitoredServiceSchema";
import Icon from "../components/Icon";
import PageState from "../components/PageState";
import "../styles/services.css";

type RegistrationField = {
    name: keyof CreateMonitoredServiceFormData;
    label: string;
    hint: string;
    placeholder?: string;
    options?: readonly string[];
    wide?: boolean;
};

const informationFields: RegistrationField[] = [
    { name: "name", label: "Name", hint: "Choose a recognisable name, up to 120 characters.", placeholder: "e.g. Payments API", wide: true },
    { name: "owner", label: "Owner", hint: "Name the responsible team or person, up to 100 characters.", placeholder: "e.g. Payments team", wide: true },
    { name: "serviceType", label: "Service type", hint: "Choose the service's protocol.", options: serviceTypes },
    { name: "environment", label: "Environment", hint: "Choose the environment this service belongs to.", options: environmentValues },
];
const endpointFields: RegistrationField[] = [
    { name: "baseUrl", label: "Base URL", hint: "Use a complete HTTP or HTTPS URL.", placeholder: "https://api.example.com", wide: true },
    { name: "healthEndpoint", label: "Health Endpoint", hint: "Use a path starting with /, such as /health.", placeholder: "/health", wide: true },
];

export default function AddServicePage() {
    const { currentOrganisation, organisationsPending, organisationsError } = useOrganisation();
    if (organisationsPending) return <PageState variant="loading" title="Loading organisations..." />;
    if (organisationsError) return <PageState variant="error" title="Unable to load organisations">{organisationsError}</PageState>;
    if (!currentOrganisation) return <PageState variant="empty" title="Select an organisation">Choose an organisation before registering a service.</PageState>;
    return <ServiceRegistration key={currentOrganisation.id} organisationId={currentOrganisation.id} organisationName={currentOrganisation.name} />;
}

function ServiceRegistration({ organisationId, organisationName }: { organisationId: string; organisationName: string }) {
    const { register, handleSubmit, formState: { errors, isSubmitting } } = useForm<CreateMonitoredServiceFormData>({
        resolver: zodResolver(createMonitoredServiceSchema),
        mode: "onTouched",
        reValidateMode: "onChange",
        defaultValues: { name: "", owner: "", serviceType: "HTTP", environment: "DEVELOPMENT", baseUrl: "", healthEndpoint: "" },
    });
    const [focusedField, setFocusedField] = useState<string | null>(null);
    const active = useRef(false);
    const submissionInFlight = useRef(false);
    const queryClient = useQueryClient();
    const navigate = useNavigate();

    useEffect(() => {
        active.current = true;
        return () => { active.current = false; };
    }, []);

    const createMutation = useMutation({
        mutationFn: (request: CreateMonitoredServiceFormData) => createMonitoredService(organisationId, request),
        onSuccess: async (createdService) => {
            await queryClient.invalidateQueries({ queryKey: ["monitored-services", organisationId] });
            // A request for the previous tenant must not navigate the new tenant.
            if (active.current) navigate(`/services/${createdService.id}`);
        },
    });
    const pending = isSubmitting || createMutation.isPending;

    async function onSubmit(data: CreateMonitoredServiceFormData) {
        if (submissionInFlight.current) return;
        submissionInFlight.current = true;
        try {
            await createMutation.mutateAsync(data);
        } catch {
            // The mutation error is displayed below; keep the user's form values.
        } finally {
            submissionInFlight.current = false;
        }
    }

    function renderField(field: RegistrationField) {
        const registration = register(field.name);
        const error = errors[field.name];
        const showHint = focusedField === field.name && !error;
        const feedbackId = `service-${field.name}-feedback`;
        const controlProps = {
            ...registration,
            id: `service-${field.name}`,
            required: true,
            disabled: pending,
            "aria-invalid": Boolean(error),
            "aria-describedby": error || showHint ? feedbackId : undefined,
            onFocus: () => setFocusedField(field.name),
            onBlur: (event: FocusEvent<HTMLInputElement | HTMLSelectElement>) => {
                void registration.onBlur(event);
                setFocusedField(null);
            },
        };
        return (
            <div key={field.name} className={`form-field registration-field ${field.wide ? "registration-field-wide" : ""}`}>
                <label htmlFor={controlProps.id}>{field.label}</label>
                {field.options ? <select {...controlProps}>{field.options.map((option) => (
                    <option key={option} value={option}>{field.name === "environment" ? option[0] + option.slice(1).toLowerCase() : option}</option>
                ))}</select> : <input {...controlProps} type="text" placeholder={field.placeholder}
                    inputMode={field.name === "baseUrl" ? "url" : "text"} autoComplete="off"
                    spellCheck={field.name === "baseUrl" || field.name === "healthEndpoint" ? false : undefined} />}
                <div id={feedbackId} className="registration-feedback">
                    {error ? <p className="validation-error" role="alert">{error.message}</p>
                        : showHint ? <p className="registration-hint">{field.hint}</p> : null}
                </div>
            </div>
        );
    }

    return (
        <div className="add-service-page service-registration-page">
            <Link to="/services" className="registration-back-link"><Icon name="chevronLeft" />Back to services</Link>
            <header className="registration-page-header"><p className="registry-eyebrow">Service registry</p><h1>Add service</h1>
                <p className="registry-muted">Register a service in <strong>{organisationName || "your organisation"}</strong>.</p>
                <p className="registration-required">All fields are required.</p>
            </header>
            <form className="standard-form service-registration-form" onSubmit={(event) => { void handleSubmit(onSubmit)(event); }} noValidate aria-label="Register service" aria-busy={pending}>
                <div className="registration-sections">
                    <section className="card registration-section" aria-labelledby="registration-information-title">
                        <div className="registration-section-heading"><h2 id="registration-information-title">Service information</h2><p className="registry-muted">Identity, ownership and environment</p></div>
                        <div className="registration-fields">{informationFields.map(renderField)}</div>
                    </section>
                    <section className="card registration-section" aria-labelledby="registration-endpoints-title">
                        <div className="registration-section-heading"><h2 id="registration-endpoints-title">Endpoints</h2><p className="registry-muted">Connection details for this service</p></div>
                        <div className="registration-fields">{endpointFields.map(renderField)}</div>
                        <p className="registration-monitoring-note">Enabled HTTP services are checked automatically after registration. Other protocols are not monitored yet.</p>
                    </section>
                </div>
                {createMutation.isError && <div role="alert" className="registration-error"><strong>Unable to register service</strong><p>{createMutation.error.message}</p><p>Your entries are preserved. Review the error before trying again.</p></div>}
                {pending && <p role="status" className="registration-progress">Registering service. Please wait...</p>}
                <footer className="registration-actions">
                    <Link to="/services" className="button button-secondary" aria-disabled={pending} tabIndex={pending ? -1 : undefined}
                        onClick={(event) => { if (pending) event.preventDefault(); }}>Cancel</Link>
                    <button type="submit" disabled={pending}>{pending ? "Adding service..." : "Add service"}</button>
                </footer>
            </form>
        </div>
    );
}
