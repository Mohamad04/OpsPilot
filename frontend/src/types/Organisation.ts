export interface Organisation {
    id: string;
    name: string;
    slug: string;
    status: "ACTIVE" | "INACTIVE";
    createdAt: string;
}