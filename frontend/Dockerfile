FROM node:20-alpine AS build
WORKDIR /app
COPY package.json ./
RUN npm install
COPY . .
RUN npm run build

FROM nginx:alpine
COPY --from=build /app/dist /usr/share/nginx/html
# Templated conf: the nginx entrypoint runs envsubst on /etc/nginx/templates/*.template
# at container start → /etc/nginx/conf.d/default.conf. Injects ${MCP_API_TOKEN} from
# the environment (NGINX_ENVSUBST_FILTER pins substitution to that one var so nginx's
# own $host/$uri/etc. are left intact). Keeps the MCP secret out of the image + git.
COPY nginx.conf.template /etc/nginx/templates/default.conf.template
EXPOSE 80 443
