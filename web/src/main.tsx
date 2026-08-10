import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import App from "./App";
import "./styles/tokens.css";
import "./styles/base.css";
import "./styles/components.css";
import "./styles/sections.css";

const root = document.getElementById("root");

if (!root) {
    throw new Error("Root element #root not found");
}

createRoot(root).render(
    <StrictMode>
        <App />
    </StrictMode>,
);
