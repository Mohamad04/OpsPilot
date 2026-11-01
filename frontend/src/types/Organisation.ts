export interface Organisation {
    id: string;
    name: string;
    slug: string;
    status: "ACTIVE" | "INACTIVE";
    createdAt: string;
    // Optional for compatibility with older API deployments; missing context is read-only.
    currentUserId?: string;
    currentUserRole?: "OWNER" | "ADMIN" | "ENGINEER" | "VIEWER";
}
