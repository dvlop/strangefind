/*
 *  StrangeFind, an anomaly detection system for the OpenDiamond Platform
 *
 *  Copyright (c) 2007-2008 Carnegie Mellon University
 *  All rights reserved.
 *
 *  StrangeFind is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, version 2.
 *
 *  StrangeFind is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with StrangeFind. If not, see <http://www.gnu.org/licenses/>.
 *
 *  Linking StrangeFind statically or dynamically with other modules is
 *  making a combined work based on StrangeFind. Thus, the terms and
 *  conditions of the GNU General Public License cover the whole
 *  combination.
 *
 *  In addition, as a special exception, the copyright holders of
 *  StrangeFind give you permission to combine StrangeFind with free software
 *  programs or libraries that are released under the GNU LGPL or the
 *  Eclipse Public License 1.0. You may copy and distribute such a system
 *  following the terms of the GNU GPL for StrangeFind and the licenses of
 *  the other code concerned, provided that you include the source code of
 *  that other code when and as the GNU GPL requires distribution of source
 *  code.
 *
 *  Note that people who make modified versions of StrangeFind are not
 *  obligated to grant this special exception for their modified versions;
 *  it is their choice whether to do so. The GNU General Public License
 *  gives permission to release a modified version without this exception;
 *  this exception also makes it possible to release a modified version
 *  which carries forward this exception.
 */

#define _GNU_SOURCE
#include <stdlib.h>
#include <stdio.h>
#include <math.h>
#include <string.h>
#include <glib.h>

#include "lib_filter.h"
#include "logic-stack-machine.h"


static const char *COUNT_KEY = "count";
static const char *SUM_KEY = "sum";
static const char *SUM_OF_SQUARES_KEY = "sum_of_squares";

typedef struct {
  int size;
  char **name_array;
  double *stddev_array;
  lf_session_variable_t **stats;
  int min_count;

  gchar **logic_code;
  logic_stack_machine_t *lsmr;
} context_t;

static double composer_sum(double a, double b) {
  printf(" %g + %g\n", a, b);
  return a + b;
}


static bool run_logic_engine(gchar **logic_code,
			     const bool *logic_values,
			     logic_stack_machine_t *lsmr,
			     int size) {
  for (gchar **ptr = logic_code; *ptr != NULL; ptr++) {
    const gchar *inst = *ptr;
    int i;

    switch(inst[0]) {
    case '&':
      lsm_and(lsmr);
      break;

    case '|':
      lsm_or(lsmr);
      break;

    case '!':
      lsm_not(lsmr);
      break;

    default:
      // number
      i = atoi(inst);
      g_assert(i < size);
      lsm_push(lsmr, logic_values[i]);
    }
  }

  int val = lsm_pop(lsmr);
  g_assert(val != -1);

  // make sure stack is empty
  g_assert(lsm_pop(lsmr) == -1);

  return val;
}


// 3 functions for diamond filter interface
int f_init_afilter (int num_arg, char **args,
		    int bloblen, void *blob_data,
		    const char *filter_name,
		    void **filter_args) {
  int i;

  // check args
  if (num_arg < 2) {
    return -1;
  }

  // make space for things to examine
  context_t *ctx = (context_t *) malloc(sizeof(context_t));

  // args is:
  // 1. min_count
  // 2. random string to supress caching
  // 3. code for the logic stack machine
  // rest. pairs of attribute names and standard deviations
  ctx->size = (num_arg - 3) / 2;

  printf("uuid: %s\n", args[1]);

  // fill in
  ctx->name_array = (char **) calloc(ctx->size, sizeof(char *));
  ctx->stddev_array = (double *) calloc(ctx->size, sizeof(double));
  ctx->min_count = strtol(args[0], NULL, 10);
  ctx->logic_code = g_strsplit(args[2], "_", -1);

  // initialize logic
  ctx->lsmr = lsm_create();

  // null terminated stats list
  int stats_len = 3 * ctx->size;
  ctx->stats = calloc(stats_len + 1, sizeof(lf_session_variable_t *));
  for (i = 0; i < stats_len; i++) {
    ctx->stats[i] = malloc(sizeof(lf_session_variable_t));
    ctx->stats[i]->composer = composer_sum;
  }

  // fill in arrays
  for (i = 0; i < ctx->size; i++) {
    ctx->name_array[i] = strdup(args[(i*2)+3]);
    ctx->stddev_array[i] = strtod(args[(i*2)+4], NULL);

    char *name;
    asprintf(&name, "%s_%s", ctx->name_array[i], COUNT_KEY);
    ctx->stats[(i * 3) + 0]->name = name;
    printf(" %d: %s\n", i, ctx->stats[(i * 3) + 0]->name);
    asprintf(&name, "%s_%s", ctx->name_array[i], SUM_KEY);
    ctx->stats[(i * 3) + 1]->name = name;
    printf(" %d: %s\n", i, ctx->stats[(i * 3) + 1]->name);
    asprintf(&name, "%s_%s", ctx->name_array[i], SUM_OF_SQUARES_KEY);
    ctx->stats[(i * 3) + 2]->name = name;
    printf(" %d: %s\n", i, ctx->stats[(i * 3) + 2]->name);
  }

  // ready?
  *filter_args = ctx;
  return 0;
}



int f_eval_afilter (lf_obj_handle_t ohandle, void *filter_args) {
  // afilter
  context_t *ctx = (context_t *) filter_args;

  int i;
  int err;

  // for attributes from diamond
  size_t len;

  // get stats
  lf_get_session_variables(ohandle, ctx->stats);

  // make array for logic literals
  bool *logic_values = calloc(ctx->size, sizeof(bool));

  // compute anomalousness for each thing
  // XXX stats done by non-statistician
  for (i = 0; i < ctx->size; i++) {
    // get each thing from string
    unsigned char *str;
    err = lf_ref_attr(ohandle, ctx->name_array[i], &len, &str);
    char *tmp = calloc(len + 1, 1);
    strncpy(tmp, (char *) str, len);
    double d = strtod(tmp, NULL);
    printf(" %s = %g\n", ctx->name_array[i], d);
    free(tmp);


    // check
    int count = ctx->stats[(i * 3) + 0]->value;
    double sum = ctx->stats[(i * 3) + 1]->value;
    double sum_sq = ctx->stats[(i * 3) + 2]->value;

    double mean = sum / count;
    double variance = (sum_sq - mean * sum) / count;
    double stddev = sqrt(variance);

    double num_stddev = ctx->stddev_array[i];
    if (count > ctx->min_count
	&& (d > mean + (num_stddev * stddev)
	    || d < mean - (num_stddev * stddev))) {
      // flag it
      printf(" *** %s is anomalous: %g (mean: %g, stddev: %g)\n",
	     ctx->name_array[i], d, mean, stddev);

      logic_values[i] = true;
    }

    // record for posterity
    asprintf(&tmp, "anomaly-value-%d.int", i);
    lf_write_attr(ohandle, tmp, sizeof(int), (unsigned char *) &i);
    free(tmp);

    asprintf(&tmp, "anomaly-value-count-%d.int", i);
    lf_write_attr(ohandle, tmp, sizeof(int), (unsigned char *) &count);
    free(tmp);

    asprintf(&tmp, "anomaly-value-mean-%d.int", i);
    lf_write_attr(ohandle, tmp, sizeof(double), (unsigned char *) &mean);
    free(tmp);

    asprintf(&tmp, "anomaly-value-stddev-%d.int", i);
    lf_write_attr(ohandle, tmp, sizeof(double), (unsigned char *) &stddev);
    free(tmp);

    // add to sum
    printf("%s: %g\n", ctx->name_array[i], d);
    ctx->stats[(i * 3) + 0]->value = 1;      // count += 1
    ctx->stats[(i * 3) + 1]->value = d;      // sum += d
    ctx->stats[(i * 3) + 2]->value = d * d;  // sum_sq += (d * d)
  }

  // update
  lf_update_session_variables(ohandle, ctx->stats);

  // run the logic engine
  int result = run_logic_engine(ctx->logic_code,
				logic_values,
				ctx->lsmr, ctx->size);
  free(logic_values);

  return result;
}



int f_fini_afilter (void *filter_args) {
  context_t *ctx = (context_t *) filter_args;

  int i;
  for (i = 0; i < ctx->size; i++) {
    free(ctx->name_array[i]);
  }

  for (i = 0; i < ctx->size * 3; i++) {
    free(ctx->stats[i]);
  }

  lsm_destroy(ctx->lsmr);
  g_strfreev(ctx->logic_code);
  free(ctx->name_array);
  free(ctx->stddev_array);
  free(ctx->stats);

  free(ctx);
  return 0;
}
