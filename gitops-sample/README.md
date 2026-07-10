# forseti-devsecops-gitops (sample)

Contenido de referencia del **repo separado** `forseti-devsecops-gitops`.

En la demo real este contenido vive en:
`https://github.com/TARIUS-S-A-S/forseti-devsecops-gitops`

Separamos código de deploy config (best practice GitOps): así el CI pipeline
que bumpea tags NO puede tocar el código, y el commit de deploy es auditable.
