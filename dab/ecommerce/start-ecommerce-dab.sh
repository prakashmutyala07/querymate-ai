#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/../.."

env_value() {
  local key="$1"
  sed -n "s/^${key}=//p" .env | tail -n 1
}

export ECOM_MSSQL_CONNECTION_STRING="${ECOM_MSSQL_CONNECTION_STRING:-$(env_value ECOM_MSSQL_CONNECTION_STRING)}"
DAB_MCP_BASE_URL="${DAB_MCP_BASE_URL:-$(env_value DAB_MCP_BASE_URL)}"
DAB_ENVIRONMENT="${DAB_ENVIRONMENT:-local}"

if [[ -z "${DAB_LOG_LEVEL:-}" ]]; then
  case "$DAB_ENVIRONMENT" in
    local|dev|development)
      DAB_LOG_LEVEL="Debug"
      ;;
    *)
      DAB_LOG_LEVEL="Error"
      ;;
  esac
fi

ASPNETCORE_URLS="${DAB_MCP_BASE_URL:-http://localhost:5001}" \
  DAB_ENVIRONMENT="$DAB_ENVIRONMENT" \
  dab start --config dab/ecommerce/dab-config.json --no-https-redirect --LogLevel "$DAB_LOG_LEVEL"
