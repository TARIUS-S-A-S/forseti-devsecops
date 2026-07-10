-- Forseti — Sprint 2: Seed del catálogo COMPLETO de obligaciones de Ecuador (RNF-8).
--
-- Estos son DATOS. Cuando cambie la ley, se agrega/modifica un registro acá (o desde la UI)
-- y NO hay que redeplegar código. Esa es la regla madre del Sprint 2.
--
-- Fuente: anexo-obligaciones-contadora.md (checklist A–E).
-- Cada empresa activa las suyas en obligacion_empresa según su perfil.

-- ╔════════════════════════════════════════════════════════╗
-- ║  A. SRI — Declaraciones                                 ║
-- ╚════════════════════════════════════════════════════════╝
INSERT INTO obligacion_catalogo (codigo, nombre, descripcion, categoria, periodicidad, regla_fecha, aplica_si, bloqueante, alerta_dias, orden) VALUES
('SRI_IVA_MENSUAL',
 'Declaración de IVA (F104) mensual',
 'Formulario 104 mensual. Aplica a régimen general y a RIMPE con periodicidad mensual.',
 'SRI_DECLARACION', 'MENSUAL',
 'Vencimiento según 9.º dígito del RUC, mes siguiente al periodo',
 '{"periodicidad_iva":["MENSUAL"]}'::jsonb,
 true, ARRAY[30,15,5], 10),

('SRI_IVA_SEMESTRAL',
 'Declaración de IVA (F104) semestral',
 'Formulario 104 semestral. Aplica a RIMPE con ingresos bajo umbral.',
 'SRI_DECLARACION', 'SEMESTRAL',
 '1.er semestre: hasta julio; 2.º semestre: hasta enero — según 9.º dígito RUC',
 '{"periodicidad_iva":["SEMESTRAL"]}'::jsonb,
 true, ARRAY[30,15,5], 20),

('SRI_RENTA_RIMPE',
 'Impuesto a la Renta — RIMPE (anual)',
 'Tabla progresiva del régimen RIMPE (negocio popular o emprendedor).',
 'SRI_DECLARACION', 'ANUAL',
 'Marzo del año siguiente, según 9.º dígito RUC',
 '{"regimen":["RIMPE_NP","RIMPE_EMPRENDEDOR"]}'::jsonb,
 true, ARRAY[60,30,15,5], 30),

('SRI_RENTA_GENERAL',
 'Impuesto a la Renta — régimen general (anual)',
 'Conciliación tributaria. Aplica a sociedades en régimen general y a personas naturales obligadas.',
 'SRI_DECLARACION', 'ANUAL',
 'Marzo/abril del año siguiente, según 9.º dígito RUC',
 '{"regimen":["GENERAL"]}'::jsonb,
 true, ARRAY[60,30,15,5], 40),

('SRI_F103_RETENCIONES',
 'Retenciones en la fuente (F103)',
 'Formulario 103 — retenciones del impuesto a la renta. Solo agentes de retención.',
 'SRI_DECLARACION', 'MENSUAL',
 'Mes siguiente al periodo, según 9.º dígito RUC',
 '{"agente_retencion":[true]}'::jsonb,
 true, ARRAY[30,15,5], 50);

-- ╔════════════════════════════════════════════════════════╗
-- ║  B. SRI — Anexos (XML)                                  ║
-- ╚════════════════════════════════════════════════════════╝
INSERT INTO obligacion_catalogo (codigo, nombre, descripcion, categoria, periodicidad, regla_fecha, aplica_si, bloqueante, alerta_dias, orden) VALUES
('SRI_ATS_MENSUAL',
 'Anexo Transaccional Simplificado (ATS) mensual',
 'Detalle de compras, ventas y retenciones en formato XML, vía portal SRI en línea.',
 'SRI_ANEXO', 'MENSUAL',
 'Mes siguiente al periodo, según 9.º dígito RUC',
 '{"periodicidad_iva":["MENSUAL"]}'::jsonb,
 true, ARRAY[30,15,5], 60),

('SRI_ATS_SEMESTRAL',
 'Anexo Transaccional Simplificado (ATS) semestral',
 'Detalle de compras, ventas y retenciones del semestre en XML.',
 'SRI_ANEXO', 'SEMESTRAL',
 '1.er semestre: julio; 2.º semestre: enero — según 9.º dígito RUC',
 '{"periodicidad_iva":["SEMESTRAL"]}'::jsonb,
 true, ARRAY[30,15,5], 70),

('SRI_APS_ANUAL',
 'Anexo de Accionistas, Partícipes y Socios (APS)',
 'Composición societaria al cierre del ejercicio. XML al SRI.',
 'SRI_ANEXO', 'ANUAL',
 'Febrero del año siguiente al cierre',
 '{"tipo_contribuyente":["SA","SAS","LTDA","EP"]}'::jsonb,
 true, ARRAY[30,15,5], 80),

('SRI_ADI_DIVIDENDOS',
 'Anexo de Dividendos (ADI)',
 'Solo si se distribuyeron utilidades en el ejercicio.',
 'SRI_ANEXO', 'ANUAL',
 'Junio del año siguiente',
 '{"tipo_contribuyente":["SA","SAS","LTDA","EP"]}'::jsonb,
 false, ARRAY[30,15], 90),

('SRI_RDEP',
 'Anexo de Retenciones en Relación de Dependencia (RDEP)',
 'Resumen de retenciones a empleados en relación de dependencia.',
 'SRI_ANEXO', 'ANUAL',
 'Enero/febrero del año siguiente',
 '{"tiene_empleados":[true]}'::jsonb,
 true, ARRAY[30,15,5], 100);

-- ╔════════════════════════════════════════════════════════╗
-- ║  C. Superintendencia de Compañías                       ║
-- ╚════════════════════════════════════════════════════════╝
INSERT INTO obligacion_catalogo (codigo, nombre, descripcion, categoria, periodicidad, regla_fecha, aplica_si, bloqueante, alerta_dias, orden) VALUES
('SUPERCIA_EEFF',
 'Estados financieros anuales (SuperCIA)',
 'Situación financiera, resultados, flujo de efectivo, cambios en el patrimonio + notas — NIIF para PYMES.',
 'SUPERCIA', 'ANUAL',
 'Hasta el 30 de abril del año siguiente',
 '{"tipo_contribuyente":["SA","SAS","LTDA","EP"]}'::jsonb,
 true, ARRAY[60,30,15,5], 110),

('SUPERCIA_INFORME_GERENTE',
 'Informe del administrador/gerente',
 'Informe anual del administrador a los socios y a la SuperCIA.',
 'SUPERCIA', 'ANUAL',
 'Junto con los estados financieros',
 '{"tipo_contribuyente":["SA","SAS","LTDA","EP"]}'::jsonb,
 true, ARRAY[60,30,15,5], 120),

('SUPERCIA_NOMINA_SOCIOS',
 'Nómina de socios/accionistas y administradores',
 'Listado actualizado al cierre del ejercicio. Acompaña a los EEFF.',
 'SUPERCIA', 'ANUAL',
 'Junto con los estados financieros',
 '{"tipo_contribuyente":["SA","SAS","LTDA","EP"]}'::jsonb,
 true, ARRAY[60,30,15,5], 130),

('SUPERCIA_INFORME_COMISARIO',
 'Informe del comisario',
 'Solo S.A. con comisario nombrado (S.A.S. normalmente no).',
 'SUPERCIA', 'ANUAL',
 'Junto con los estados financieros',
 '{"tipo_contribuyente":["SA"]}'::jsonb,
 false, ARRAY[30,15], 140),

('SUPERCIA_CONTRIBUCION',
 'Contribución anual a la SuperCIA',
 'Aporte anual; hay exención por monto de activos.',
 'SUPERCIA', 'ANUAL',
 'Aprox. septiembre',
 '{"tipo_contribuyente":["SA","SAS","LTDA","EP"]}'::jsonb,
 true, ARRAY[30,15,5], 150);

-- ╔════════════════════════════════════════════════════════╗
-- ║  D. Municipio de Quito + permisos                       ║
-- ╚════════════════════════════════════════════════════════╝
INSERT INTO obligacion_catalogo (codigo, nombre, descripcion, categoria, periodicidad, regla_fecha, aplica_si, bloqueante, alerta_dias, orden) VALUES
('MUN_QUITO_PATENTE',
 'Patente municipal — Quito',
 'Impuesto anual a las actividades económicas en el cantón.',
 'MUNICIPIO', 'ANUAL',
 'Calendario municipal — según último dígito RUC',
 '{"municipio":["QUITO"]}'::jsonb,
 true, ARRAY[30,15,5], 160),

('MUN_QUITO_1_5_X_MIL',
 '1.5 por mil sobre activos totales',
 'Impuesto municipal sobre activos. Quito.',
 'MUNICIPIO', 'ANUAL',
 'Calendario municipal — junto con la patente',
 '{"municipio":["QUITO"]}'::jsonb,
 true, ARRAY[30,15,5], 170),

('MUN_QUITO_LUAE',
 'LUAE — Licencia Única de Actividades Económicas',
 'Renovación anual de licencia para actividades en local físico.',
 'MUNICIPIO', 'ANUAL',
 'Aniversario de emisión',
 '{"tiene_local":[true]}'::jsonb,
 false, ARRAY[30,15,5], 180),

('PERMISO_BOMBEROS',
 'Permiso de Funcionamiento — Bomberos',
 'Renovación anual del permiso de bomberos del local.',
 'MUNICIPIO', 'ANUAL',
 'Aniversario',
 '{"tiene_local":[true]}'::jsonb,
 false, ARRAY[30,15,5], 190);

-- ╔════════════════════════════════════════════════════════╗
-- ║  E. IESS / Ministerio de Trabajo                        ║
-- ╚════════════════════════════════════════════════════════╝
INSERT INTO obligacion_catalogo (codigo, nombre, descripcion, categoria, periodicidad, regla_fecha, aplica_si, bloqueante, alerta_dias, orden) VALUES
('IESS_PLANILLAS',
 'Planillas IESS de aportes',
 'Aportes patronales y personales. Pago mensual.',
 'IESS_MDT', 'MENSUAL',
 'Día 15 del mes siguiente',
 '{"tiene_empleados":[true]}'::jsonb,
 true, ARRAY[10,5,2], 200),

('MDT_DECIMO_TERCERO',
 'Décimo tercer sueldo',
 'Pago hasta el 24 de diciembre. Aviso al SUT.',
 'IESS_MDT', 'ANUAL',
 'Hasta 24-diciembre',
 '{"tiene_empleados":[true]}'::jsonb,
 true, ARRAY[30,15,5], 210),

('MDT_DECIMO_CUARTO',
 'Décimo cuarto sueldo',
 'Sierra/Amazonía: hasta 15-agosto. Costa/Galápagos: hasta 15-marzo.',
 'IESS_MDT', 'ANUAL',
 'Sierra/Amazonía: 15-ago. Costa/Galápagos: 15-mar.',
 '{"tiene_empleados":[true]}'::jsonb,
 true, ARRAY[30,15,5], 220),

('MDT_UTILIDADES',
 'Participación de utilidades 15%',
 'A trabajadores. Hasta el 15 de abril del año siguiente.',
 'IESS_MDT', 'ANUAL',
 'Hasta 15-abril',
 '{"tiene_empleados":[true]}'::jsonb,
 true, ARRAY[30,15,5], 230);

-- ╔════════════════════════════════════════════════════════╗
-- ║  F. Internas / Forseti                                  ║
-- ╚════════════════════════════════════════════════════════╝
INSERT INTO obligacion_catalogo (codigo, nombre, descripcion, categoria, periodicidad, regla_fecha, aplica_si, bloqueante, alerta_dias, orden) VALUES
('INTERNA_CADUCIDAD_P12',
 'Caducidad del certificado de firma (.p12)',
 'Alerta de renovación del certificado de firma electrónica.',
 'INTERNA', 'EVENTUAL',
 'Fecha de caducidad del certificado activo',
 '{}'::jsonb,
 true, ARRAY[60,30,15,5], 240),

('INTERNA_RENOVACION_DOMINIO',
 'Renovación de dominio web',
 'Alerta de renovación de dominios registrados.',
 'INTERNA', 'EVENTUAL',
 'Fecha de vencimiento de cada dominio',
 '{}'::jsonb,
 false, ARRAY[60,30,15], 250);
