# IAM Console

React-based administrative and end-user console for Wyrmgate IAM.

The console follows the product navigation and job-oriented UX defined in the IAM v2 documentation rather than mirroring backend modules mechanically.

Frontend rules:

- use a typed API client
- no permanent mock business data
- backend-derived authorization/permissions
- feature-level routes and reusable components
- explicit query-cache invalidation
- business rules remain server-side

Exact React tooling, package manager, and build versions will be fixed when the frontend build is bootstrapped.
