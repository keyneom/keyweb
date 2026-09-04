import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { App } from "./App";
import "./ui/tokens.css";
import "./ui/kit.css";

const root = document.getElementById("root");
if (!root) throw new Error("Keyweb could not find its root element.");

createRoot(root).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
