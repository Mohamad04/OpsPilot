function DashboardPage(){
    return (
        <div className="dashboard-page">
            <h1>Dashboard</h1>
            <div className="dashboard-grid">
                <div className="dashboard-card">
                    <span className="card-title">
  Monitored services with an extremely long title that should not fit inside this dashboard card
</span>
                    <span className="card-value">0</span>
                </div>
                <div className="dashboard-card">
                    <span className="card-title">Active alerts</span>
                    <span className="card-value">0</span>
                </div>
                <div className="dashboard-card">
                    <span className="card-title">Incidents</span>
                    <span className="card-value">0</span>
                </div>
            </div>
            <p>Welcome to the dashboard!</p>
        </div>
    );
}
export default DashboardPage;